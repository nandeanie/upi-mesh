package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private ServerKeyHolder        serverKey;
    @Autowired private DemoService            demo;
    @Autowired private MeshSimulatorService   mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository      accountRepo;
    @Autowired private TransactionRepository  txRepo;
    @Autowired private IdempotencyService     idempotency;

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey",    serverKey.getPublicKeyBase64(),
                "algorithm",    "RSA-2048",
                "hybridScheme", "RSA-OAEP-SHA256 wraps AES-256-GCM session key"
        );
    }

    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@Valid @RequestBody DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);
        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);
        return ResponseEntity.ok(Map.of(
                "packetId",          packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl",               packet.getTtl(),
                "injectedAt",        startDevice
        ));
    }

    @PostMapping("/demo/run-full")
    public ResponseEntity<?> runFullDemo(@Valid @RequestBody DemoSendRequest req) throws Exception {
        mesh.resetMesh();
        idempotency.clear();

        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);
        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        MeshSimulatorService.GossipResult g1 = mesh.gossipOnce();
        MeshSimulatorService.GossipResult g2 = mesh.gossipOnce();

        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> flushResults = new ArrayList<>();
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (flushResults) {
                flushResults.add(Map.of(
                        "bridgeNode",    up.bridgeNodeId(),
                        "packetId",      up.packet().getPacketId().substring(0, 8),
                        "outcome",       r.outcome(),
                        "reason",        r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return ResponseEntity.ok(Map.of(
                "packetId",        packet.getPacketId(),
                "gossipRound1",    g1.transfers(),
                "gossipRound2",    g2.transfers(),
                "meshAfterGossip", g2.deviceCounts(),
                "flushAttempted",  uploads.size(),
                "flushResults",    flushResults
        ));
    }

    @PostMapping("/demo/reset-full")
    public Map<String, Object> resetFull() {
        mesh.resetMesh();
        idempotency.clear();
        demo.resetBalances();
        return Map.of("status", "full reset complete");
    }

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId",    d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds",   d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8)).toList()
            ));
        }
        deviceData.sort(Comparator.comparing(m -> (Boolean) m.get("hasInternet")));
        return Map.of("devices", deviceData, "idempotencyCacheSize", idempotency.size());
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of("transfers", r.transfers(), "deviceCounts", r.deviceCounts());
    }

    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> results = new ArrayList<>();
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode",    up.bridgeNodeId(),
                        "packetId",      up.packet().getPacketId().substring(0, 8),
                        "outcome",       r.outcome(),
                        "reason",        r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });
        return Map.of("uploadsAttempted", uploads.size(), "results", results);
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and cache cleared");
    }

    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count",      defaultValue = "0")       int hopCount) {
        return ResponseEntity.ok(bridge.ingest(packet, bridgeNodeId, hopCount));
    }

    @GetMapping("/accounts")
    public List<Account> listAccounts() { return accountRepo.findAll(); }

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() { return txRepo.findTop50ByOrderByIdDesc(); }

    @GetMapping("/audit")
    public Map<String, Object> auditLog() {
        List<Transaction> all = txRepo.findTop50ByOrderByIdDesc();
        return Map.of(
                "summary", Map.of(
                        "totalSettled",         txRepo.countByStatus(Transaction.Status.SETTLED),
                        "totalRejected",        txRepo.countByStatus(Transaction.Status.REJECTED),
                        "totalInvalid",         txRepo.countByStatus(Transaction.Status.INVALID),
                        "idempotencyCacheSize", idempotency.size()
                ),
                "recentTransactions", all
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "upi-offline-mesh",
                      "timestamp", java.time.Instant.now().toString());
    }

    public static class DemoSendRequest {
        @NotBlank  public String     senderVpa;
        @NotBlank  public String     receiverVpa;
        @NotNull @Positive
                   public BigDecimal amount;
        @NotBlank  public String     pin;
                   public Integer    ttl;
                   public String     startDevice;
    }
}

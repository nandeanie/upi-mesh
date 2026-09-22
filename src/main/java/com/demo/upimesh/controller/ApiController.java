package com.demo.upimesh.controller;

<<<<<<< HEAD
import com.demo.upimesh.crypto.HybridCryptoService;
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
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
<<<<<<< HEAD
import java.util.concurrent.*;
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88

@RestController
@RequestMapping("/api")
public class ApiController {

<<<<<<< HEAD
    private static final int DEFAULT_TTL = 5;
    private static final int MAX_TTL     = 10;

    @Autowired private ServerKeyHolder        serverKey;
    @Autowired private HybridCryptoService    crypto;
=======
    @Autowired private ServerKeyHolder        serverKey;
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    @Autowired private DemoService            demo;
    @Autowired private MeshSimulatorService   mesh;
    @Autowired private BridgeIngestionService bridge;
    @Autowired private AccountRepository      accountRepo;
    @Autowired private TransactionRepository  txRepo;
    @Autowired private IdempotencyService     idempotency;
<<<<<<< HEAD
    @Autowired private MetricsService         metrics;
    @Autowired private EventLogService        eventLog;

    // ── Server key ───────────────────────────────────────────────────────────
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88

    @GetMapping("/server-key")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey",    serverKey.getPublicKeyBase64(),
                "algorithm",    "RSA-2048",
                "hybridScheme", "RSA-OAEP-SHA256 wraps AES-256-GCM session key"
        );
    }

<<<<<<< HEAD
    // ── Demo: step-by-step flow (inject → gossip → flush) ────────────────────

    @PostMapping("/demo/send")
    public ResponseEntity<?> demoSend(@Valid @RequestBody DemoSendRequest req) throws Exception {
        validateParties(req);
        int ttl = resolveTtl(req.ttl);
        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;

        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin, ttl);
        mesh.inject(startDevice, packet);
        eventLog.record("INJECT", "Encrypted ₹" + req.amount.stripTrailingZeros().toPlainString()
                + " " + req.senderVpa + " → " + req.receiverVpa
                + " and injected packet " + shortId(packet.getPacketId())
                + " at " + startDevice + " (TTL " + ttl + ")");

        return ResponseEntity.ok(Map.of(
                "packetId",          packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ciphertextLength",  packet.getCiphertext().length(),
=======
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
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
                "ttl",               packet.getTtl(),
                "injectedAt",        startDevice
        ));
    }

<<<<<<< HEAD
    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        eventLog.record("GOSSIP", r.transfers() + " packet transfer" + (r.transfers() == 1 ? "" : "s")
                + " across the mesh this round");
        return Map.of("transfers", r.transfers(), "deviceCounts", r.deviceCounts());
    }

    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<Map<String, Object>> results = flushBridges();
        return Map.of("uploadsAttempted", results.size(), "results", results);
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        eventLog.record("RESET", "Mesh and idempotency cache cleared");
        return Map.of("status", "mesh and cache cleared");
    }

    /** Toggle a phone's connectivity: an online phone is a bridge and uploads on flush. */
    @PostMapping("/mesh/devices/{deviceId}/internet")
    public Map<String, Object> setDeviceInternet(@PathVariable String deviceId,
                                                 @Valid @RequestBody InternetRequest req) {
        VirtualDevice d = mesh.setInternet(deviceId, req.enabled);
        eventLog.record("DEVICE", deviceId + (req.enabled ? " gained 4G and can now act as a bridge"
                                                          : " went offline (Bluetooth only)"));
        return Map.of("deviceId", d.getDeviceId(), "hasInternet", d.hasInternet());
=======
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
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
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
<<<<<<< HEAD
                            .map(p -> shortId(p.getPacketId())).toList()
            ));
        }
        // Offline phones first, bridges last; alphabetical inside each group so the UI never reshuffles.
        deviceData.sort(Comparator
                .comparing((Map<String, Object> m) -> (Boolean) m.get("hasInternet"))
                .thenComparing(m -> (String) m.get("deviceId")));
        return Map.of("devices", deviceData, "idempotencyCacheSize", idempotency.size());
    }

    // ── Demo: one-shot flow (kept for API compatibility) ─────────────────────

    @PostMapping("/demo/run-full")
    public ResponseEntity<?> runFullDemo(@Valid @RequestBody DemoSendRequest req) throws Exception {
        validateParties(req);
        int ttl = resolveTtl(req.ttl);

        mesh.resetMesh();
        idempotency.clear();

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin, ttl);
        mesh.inject(startDevice, packet);
        eventLog.record("INJECT", "Encrypted ₹" + req.amount.stripTrailingZeros().toPlainString()
                + " " + req.senderVpa + " → " + req.receiverVpa
                + " and injected packet " + shortId(packet.getPacketId())
                + " at " + startDevice + " (TTL " + ttl + ")");

        MeshSimulatorService.GossipResult g1 = mesh.gossipOnce();
        MeshSimulatorService.GossipResult g2 = mesh.gossipOnce();
        eventLog.record("GOSSIP", (g1.transfers() + g2.transfers()) + " packet transfers over 2 gossip rounds");

        List<Map<String, Object>> flushResults = flushBridges();

        return ResponseEntity.ok(Map.of(
                "packetId",        packet.getPacketId(),
                "gossipRound1",    g1.transfers(),
                "gossipRound2",    g2.transfers(),
                "meshAfterGossip", g2.deviceCounts(),
                "flushAttempted",  flushResults.size(),
                "flushResults",    flushResults
        ));
    }

    // ── Demo: security lab ───────────────────────────────────────────────────

    /**
     * A malicious relay flips one character of the ciphertext before uploading it.
     * AES-GCM's auth tag must catch this — expected outcome is INVALID and no money moves.
     */
    @PostMapping("/demo/tamper")
    public Map<String, Object> tamper(@Valid @RequestBody DemoSendRequest req) throws Exception {
        validateParties(req);
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin, resolveTtl(req.ttl));

        String original     = packet.getCiphertext();
        String originalHash = crypto.hashCiphertext(original);

        char[] chars = original.toCharArray();
        int idx = Math.max(0, chars.length - 12);      // inside the AES-GCM ciphertext / tag region
        chars[idx] = (chars[idx] == 'A') ? 'B' : 'A';
        packet.setCiphertext(new String(chars));

        eventLog.record("ATTACK", "Relay tampered with packet " + shortId(packet.getPacketId())
                + " (flipped 1 character at offset " + idx + ") and uploaded it");
        BridgeIngestionService.IngestResult r = bridge.ingest(packet, "attacker-node", 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome",            r.outcome());
        out.put("reason",             r.reason() == null ? "" : r.reason());
        out.put("originalPacketHash", originalHash);
        out.put("tamperedPacketHash", r.packetHash());
        out.put("flippedOffset",      idx);
        return out;
    }

    /**
     * The same packet reaches the backend through N bridges at exactly the same instant.
     * Exactly one must settle; every other upload must be dropped as a duplicate.
     */
    @PostMapping("/demo/concurrent-upload")
    public Map<String, Object> concurrentUpload(@Valid @RequestBody DemoSendRequest req) throws Exception {
        validateParties(req);
        int n = req.bridges == null ? 3 : Math.max(2, Math.min(req.bridges, 8));

        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin, resolveTtl(req.ttl));
        eventLog.record("ATTACK", n + " bridges racing to upload packet " + shortId(packet.getPacketId()));

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go    = new CountDownLatch(1);
            List<Future<BridgeIngestionService.IngestResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final String node = "bridge-" + (i + 1);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();                       // release every bridge at the same instant
                    return bridge.ingest(packet, node, 1);
                }));
            }
            ready.await();
            go.countDown();

            List<Map<String, Object>> results = new ArrayList<>();
            int settled = 0, duplicates = 0, other = 0;
            for (int i = 0; i < n; i++) {
                BridgeIngestionService.IngestResult r = futures.get(i).get(20, TimeUnit.SECONDS);
                results.add(resultMap("bridge-" + (i + 1), packet.getPacketId(), r, 1));
                if ("SETTLED".equals(r.outcome()))                 settled++;
                else if ("DUPLICATE_DROPPED".equals(r.outcome()))  duplicates++;
                else                                               other++;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("bridges",           n);
            out.put("settled",           settled);
            out.put("duplicatesDropped", duplicates);
            out.put("otherOutcomes",     other);
            out.put("results",           results);
            return out;
        } finally {
            pool.shutdown();
        }
    }

    @PostMapping("/demo/reset-full")
    public Map<String, Object> resetFull() {
        mesh.resetMesh();
        idempotency.clear();
        demo.resetBalances();
        demo.resetLedger();
        metrics.reset();
        eventLog.clear();
        eventLog.record("RESET", "Full reset: mesh, cache, balances, ledger and counters restored");
        return Map.of("status", "full reset complete");
    }

    // ── Bridge ingest (real bridge phones call this) ─────────────────────────

=======
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

>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    @PostMapping("/bridge/ingest")
    public ResponseEntity<?> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count",      defaultValue = "0")       int hopCount) {
        return ResponseEntity.ok(bridge.ingest(packet, bridgeNodeId, hopCount));
    }

<<<<<<< HEAD
    // ── Read APIs ────────────────────────────────────────────────────────────

    @GetMapping("/accounts")
    public List<Account> listAccounts() {
        return accountRepo.findAll().stream()
                .sorted(Comparator.comparing(Account::getVpa))
                .toList();
    }
=======
    @GetMapping("/accounts")
    public List<Account> listAccounts() { return accountRepo.findAll(); }
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88

    @GetMapping("/transactions")
    public List<Transaction> listTransactions() { return txRepo.findTop50ByOrderByIdDesc(); }

<<<<<<< HEAD
    @GetMapping("/events")
    public List<EventLogService.Event> events(@RequestParam(defaultValue = "50") int limit) {
        return eventLog.recent(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/audit")
    public Map<String, Object> auditLog() {
        List<Transaction> all = txRepo.findTop50ByOrderByIdDesc();
        BigDecimal volume = txRepo.sumAmountByStatus(Transaction.Status.SETTLED);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSettled",         txRepo.countByStatus(Transaction.Status.SETTLED));
        summary.put("totalRejected",        txRepo.countByStatus(Transaction.Status.REJECTED));
        summary.put("totalInvalid",         txRepo.countByStatus(Transaction.Status.INVALID)
                                            + metrics.getInvalidPackets());
        summary.put("duplicatesDropped",    metrics.getDuplicatesDropped());
        summary.put("volumeSettled",        volume == null ? BigDecimal.ZERO : volume);
        summary.put("idempotencyCacheSize", idempotency.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",            summary);
        out.put("recentTransactions", all);
        return out;
=======
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
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "upi-offline-mesh",
                      "timestamp", java.time.Instant.now().toString());
    }

<<<<<<< HEAD
    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Upload every packet held by an online device, in parallel, exactly like real bridges would. */
    private List<Map<String, Object>> flushBridges() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();
        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());

        uploads.parallelStream().forEach(up -> {
            int hops = mesh.hopCount(up.packet());
            BridgeIngestionService.IngestResult r = bridge.ingest(up.packet(), up.bridgeNodeId(), hops);
            results.add(resultMap(up.bridgeNodeId(), up.packet().getPacketId(), r, hops));
        });

        if (uploads.isEmpty()) {
            eventLog.record("FLUSH", "No online device is holding a packet — nothing to upload");
        } else {
            eventLog.record("FLUSH", uploads.size() + " packet upload" + (uploads.size() == 1 ? "" : "s")
                    + " attempted by bridge device(s)");
        }
        return new ArrayList<>(results);
    }

    private Map<String, Object> resultMap(String bridgeNode, String packetId,
                                          BridgeIngestionService.IngestResult r, int hops) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bridgeNode",    bridgeNode);
        m.put("packetId",      shortId(packetId));
        m.put("packetHash",    r.packetHash());
        m.put("outcome",       r.outcome());
        m.put("reason",        r.reason() == null ? "" : r.reason());
        m.put("transactionId", r.transactionId() == null ? -1 : r.transactionId());
        m.put("hopCount",      hops);
        return m;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static int resolveTtl(Integer ttl) {
        int v = ttl == null ? DEFAULT_TTL : ttl;
        if (v < 1 || v > MAX_TTL) {
            throw new IllegalArgumentException("ttl must be between 1 and " + MAX_TTL);
        }
        return v;
    }

    private void validateParties(DemoSendRequest req) {
        if (req.senderVpa.equals(req.receiverVpa)) {
            throw new IllegalArgumentException("Sender and receiver must be different accounts");
        }
        if (!accountRepo.existsById(req.senderVpa)) {
            throw new IllegalArgumentException("Unknown sender account: " + req.senderVpa);
        }
        if (!accountRepo.existsById(req.receiverVpa)) {
            throw new IllegalArgumentException("Unknown receiver account: " + req.receiverVpa);
        }
    }

    // ── Request bodies ───────────────────────────────────────────────────────

=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    public static class DemoSendRequest {
        @NotBlank  public String     senderVpa;
        @NotBlank  public String     receiverVpa;
        @NotNull @Positive
                   public BigDecimal amount;
        @NotBlank  public String     pin;
                   public Integer    ttl;
                   public String     startDevice;
<<<<<<< HEAD
                   public Integer    bridges;      // only used by /demo/concurrent-upload
    }

    public static class InternetRequest {
        @NotNull public Boolean enabled;
=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    }
}

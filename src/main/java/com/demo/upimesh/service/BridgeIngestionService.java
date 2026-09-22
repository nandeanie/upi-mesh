package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * The production pipeline for one inbound packet from a bridge node.
 *
 *  1. Hash ciphertext (SHA-256) — O(n) pre-work before any crypto
 *  2. Claim hash (idempotency gate) — atomic putIfAbsent
 *  3. Decrypt (RSA-OAEP + AES-GCM) — throws on any tampering
 *  4. Freshness check — reject stale/future-dated packets (replay protection)
 *  5. Settle — @Transactional debit + credit + ledger write
 *
 * All outcomes are logged with a structured line including packetHash, outcome,
 * bridgeNodeId, and duration — making every ingest traceable in production logs.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    @Autowired private HybridCryptoService  crypto;
    @Autowired private IdempotencyService   idempotency;
    @Autowired private SettlementService    settlement;
    @Autowired private MetricsService       metrics;
    @Autowired private EventLogService      events;

    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        Instant start     = Instant.now();
        String  packetHash = "?";

        try {
            // ── 1. Hash ──────────────────────────────────────────────────────
            packetHash = crypto.hashCiphertext(packet.getCiphertext());

            // ── 2. Idempotency gate ──────────────────────────────────────────
            if (!idempotency.claim(packetHash)) {
                audit(packetHash, "DUPLICATE_DROPPED", bridgeNodeId, hopCount, start, null);
                metrics.duplicateDropped();
                events.record("DUPLICATE", "Duplicate of packet " + shortHash(packetHash)
                        + " from " + bridgeNodeId + " dropped by the idempotency gate");
                return IngestResult.duplicate(packetHash);
            }

            // ── 3. Decrypt ───────────────────────────────────────────────────
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(packet.getCiphertext());
            } catch (Exception e) {
                audit(packetHash, "INVALID:decryption_failed", bridgeNodeId, hopCount, start,
                        e.getClass().getSimpleName());
                metrics.invalidPacket();
                events.record("INVALID", "Packet " + shortHash(packetHash) + " from " + bridgeNodeId
                        + " failed decryption / authentication (" + e.getClass().getSimpleName() + ")");
                return IngestResult.invalid(packetHash, "decryption_failed");
            }

            // ── 4. Freshness check (replay protection) ───────────────────────
            long ageSeconds = (Instant.now().toEpochMilli() - instruction.getSignedAt()) / 1000;
            if (ageSeconds > maxAgeSeconds) {
                audit(packetHash, "INVALID:stale_packet", bridgeNodeId, hopCount, start,
                        "age=" + ageSeconds + "s");
                metrics.invalidPacket();
                events.record("INVALID", "Packet " + shortHash(packetHash) + " rejected as stale (age "
                        + ageSeconds + "s)");
                return IngestResult.invalid(packetHash, "stale_packet (age=" + ageSeconds + "s)");
            }
            if (ageSeconds < -300) {   // 5-minute clock-skew tolerance
                audit(packetHash, "INVALID:future_dated", bridgeNodeId, hopCount, start,
                        "skew=" + (-ageSeconds) + "s");
                metrics.invalidPacket();
                events.record("INVALID", "Packet " + shortHash(packetHash) + " rejected as future-dated");
                return IngestResult.invalid(packetHash, "future_dated");
            }

            // ── 5. Settle ────────────────────────────────────────────────────
            Transaction tx = settlement.settle(instruction, packetHash, bridgeNodeId, hopCount);
            String outcome = tx.getStatus().name();
            audit(packetHash, outcome, bridgeNodeId, hopCount, start, null);
            events.record(outcome, describeSettlement(tx, bridgeNodeId, hopCount));
            return IngestResult.of(outcome, packetHash, tx);

        } catch (Exception e) {
            log.error("Ingestion pipeline error bridge={} hash={}: {}",
                    bridgeNodeId, packetHash, e.getMessage(), e);
            metrics.invalidPacket();
            events.record("INVALID", "Packet " + shortHash(packetHash) + " from " + bridgeNodeId
                    + " hit an internal error: " + e.getMessage());
            return IngestResult.invalid(packetHash, "internal_error");
        }
    }

    private static String shortHash(String hash) {
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    private static String describeSettlement(Transaction tx, String bridgeNodeId, int hopCount) {
        String hops = hopCount + (hopCount == 1 ? " hop" : " hops");
        if (tx.getStatus() == Transaction.Status.SETTLED) {
            return "Settled ₹" + tx.getAmount().stripTrailingZeros().toPlainString() + " "
                    + tx.getSenderVpa() + " → " + tx.getReceiverVpa()
                    + " via " + bridgeNodeId + " (" + hops + ")";
        }
        return "Rejected ₹" + tx.getAmount().stripTrailingZeros().toPlainString() + " "
                + tx.getSenderVpa() + " → " + tx.getReceiverVpa()
                + " (" + tx.getRejectionReason() + ")";
    }

    private void audit(String hash, String outcome, String bridge, int hops,
                       Instant start, String detail) {
        long ms = Duration.between(start, Instant.now()).toMillis();
        log.info("ingest outcome={} hash={}... bridge={} hops={} durationMs={}{}",
                outcome,
                hash.length() > 12 ? hash.substring(0, 12) : hash,
                bridge, hops, ms,
                detail != null ? " detail=" + detail : "");
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record IngestResult(String outcome, String packetHash, String reason,
                               Long transactionId, String transactionStatus) {

        public static IngestResult of(String outcome, String hash, Transaction tx) {
            return new IngestResult(outcome, hash, tx.getRejectionReason(),
                    tx.getId(), tx.getStatus().name());
        }

        public static IngestResult duplicate(String hash) {
            return new IngestResult("DUPLICATE_DROPPED", hash, null, null, null);
        }

        public static IngestResult invalid(String hash, String reason) {
            return new IngestResult("INVALID", hash, reason, null, null);
        }
    }
}

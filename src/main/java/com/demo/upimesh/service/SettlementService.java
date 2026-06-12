package com.demo.upimesh.service;

import com.demo.upimesh.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ledger settlement service.
 *
 * Wrapped in @Transactional — either BOTH debit and credit apply, or neither.
 * Uses PESSIMISTIC_WRITE lock on both accounts before touching balances,
 * preventing any concurrent settlement from seeing stale balances.
 *
 * Defense-in-depth stack:
 *   1. ConcurrentHashMap idempotency (JVM-level, first line)
 *   2. PESSIMISTIC_WRITE lock (DB-level, concurrent reads blocked)
 *   3. @Version optimistic lock (DB-level, last resort)
 *   4. Unique index on packet_hash (DB-level, absolute backstop)
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;

    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {

        Account sender = accounts.findByVpaForUpdate(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findByVpaForUpdate(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown receiver VPA: " + instruction.getReceiverVpa()));

        BigDecimal amount = instruction.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive; got: " + amount);
        }

        // ── Insufficient funds ──────────────────────────────────────────────
        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("REJECTED — insufficient funds: {} has ₹{}, tried to send ₹{}",
                    sender.getVpa(), sender.getBalance(), amount);
            return record(instruction, packetHash, bridgeNodeId, hopCount,
                    Transaction.Status.REJECTED, "insufficient_funds");
        }

        // ── Debit + Credit ─────────────────────────────────────────────────
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        Transaction tx = record(instruction, packetHash, bridgeNodeId, hopCount,
                Transaction.Status.SETTLED, null);

        log.info("SETTLED ₹{} {} → {} (hash={}... bridge={} hops={})",
                amount, sender.getVpa(), receiver.getVpa(),
                packetHash.substring(0, 12), bridgeNodeId, hopCount);
        return tx;
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private Transaction record(PaymentInstruction instruction, String packetHash,
                                String bridgeNodeId, int hopCount,
                                Transaction.Status status, String reason) {
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(status);
        tx.setRejectionReason(reason);
        return transactions.save(tx);
    }
}

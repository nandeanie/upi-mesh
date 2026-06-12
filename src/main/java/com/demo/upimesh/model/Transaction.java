package com.demo.upimesh.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable ledger record. Written once, never updated.
 *
 * packetHash is the idempotency key. The unique DB index is defense-in-depth:
 * if the in-memory idempotency cache ever fails (e.g. JVM restart mid-flight),
 * the DB constraint is the last line of defense against double-settlement.
 */
@Entity
@Table(name = "transactions",
       indexes = { @Index(name = "idx_packet_hash", columnList = "packetHash", unique = true) })
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;     // SHA-256 hex of encrypted ciphertext

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant signedAt;      // When sender originally signed (offline)

    @Column(nullable = false)
    private Instant settledAt;     // When backend processed it

    @Column(nullable = false)
    private String bridgeNodeId;   // Which mesh node delivered it

    @Column(nullable = false)
    private int hopCount;          // How many devices it traversed

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(length = 255)
    private String rejectionReason; // Populated when status != SETTLED

    public enum Status {
        SETTLED,              // Debit and credit applied
        REJECTED,             // Insufficient funds
        INVALID               // Tampered / stale / bad format
    }

    public Transaction() {}

    // ── Getters & Setters ──────────────────────────────────────────────────

    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }

    public String getPacketHash()                { return packetHash; }
    public void   setPacketHash(String h)        { this.packetHash = h; }

    public String getSenderVpa()                 { return senderVpa; }
    public void   setSenderVpa(String v)         { this.senderVpa = v; }

    public String getReceiverVpa()               { return receiverVpa; }
    public void   setReceiverVpa(String v)       { this.receiverVpa = v; }

    public BigDecimal getAmount()                { return amount; }
    public void       setAmount(BigDecimal a)    { this.amount = a; }

    public Instant getSignedAt()                 { return signedAt; }
    public void    setSignedAt(Instant i)        { this.signedAt = i; }

    public Instant getSettledAt()                { return settledAt; }
    public void    setSettledAt(Instant i)       { this.settledAt = i; }

    public String getBridgeNodeId()              { return bridgeNodeId; }
    public void   setBridgeNodeId(String b)      { this.bridgeNodeId = b; }

    public int  getHopCount()                    { return hopCount; }
    public void setHopCount(int h)               { this.hopCount = h; }

    public Status getStatus()                    { return status; }
    public void   setStatus(Status s)            { this.status = s; }

    public String getRejectionReason()           { return rejectionReason; }
    public void   setRejectionReason(String r)   { this.rejectionReason = r; }
}

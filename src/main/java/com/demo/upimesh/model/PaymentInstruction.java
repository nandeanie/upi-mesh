package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * The decrypted payload. Only the backend server ever sees this plaintext.
 *
 * Security-critical fields:
 *   nonce     — UUID unique to this payment intent. Ensures two legitimate
 *               ₹100 payments from Alice to Bob produce different ciphertexts
 *               and thus different idempotency hashes, so both settle correctly.
 *   signedAt  — epoch millis when the sender signed. Backend rejects packets
 *               older than the configured freshness window (default 24h).
 *   pinHash   — SHA-256 of the user's 4-digit UPI PIN. In production, this
 *               would be verified against a salted hash stored by the bank.
 *               Never log or persist the raw PIN.
 */
public class PaymentInstruction {

    private String     senderVpa;
    private String     receiverVpa;
    private BigDecimal amount;
    private String     pinHash;    // SHA-256 hex of user PIN
    private String     nonce;      // UUID, unique per payment intent
    private Long       signedAt;   // epoch millis

    public PaymentInstruction() {}

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt) {
        this.senderVpa   = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount      = amount;
        this.pinHash     = pinHash;
        this.nonce       = nonce;
        this.signedAt    = signedAt;
    }

    public String     getSenderVpa()               { return senderVpa; }
    public void       setSenderVpa(String v)        { this.senderVpa = v; }

    public String     getReceiverVpa()              { return receiverVpa; }
    public void       setReceiverVpa(String v)      { this.receiverVpa = v; }

    public BigDecimal getAmount()                   { return amount; }
    public void       setAmount(BigDecimal a)       { this.amount = a; }

    public String     getPinHash()                  { return pinHash; }
    public void       setPinHash(String p)          { this.pinHash = p; }

    public String     getNonce()                    { return nonce; }
    public void       setNonce(String n)            { this.nonce = n; }

    public Long       getSignedAt()                 { return signedAt; }
    public void       setSignedAt(Long s)           { this.signedAt = s; }
}

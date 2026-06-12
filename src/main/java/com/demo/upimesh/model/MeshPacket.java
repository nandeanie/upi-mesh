package com.demo.upimesh.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Over-the-wire packet format. Hops device-to-device via Bluetooth.
 *
 * Outer fields (packetId, ttl, createdAt) are readable by intermediates —
 * they need them for gossip routing and TTL management.
 *
 * ciphertext is opaque to everyone except the backend server, which holds
 * the private key.
 *
 * Security note: a malicious intermediate CAN alter outer fields. This is
 * acceptable because:
 *  - The server uses SHA-256(ciphertext) as the idempotency key, not packetId.
 *  - The ciphertext is authenticated (AES-GCM tag). Any modification to the
 *    encrypted payload causes decryption to throw — the backend rejects it.
 */
public class MeshPacket {

    @NotBlank(message = "packetId must not be blank")
    private String packetId;

    @Min(value = 0, message = "ttl must be >= 0")
    private int ttl;

    @NotNull(message = "createdAt is required")
    private Long createdAt;     // epoch millis

    @NotBlank(message = "ciphertext must not be blank")
    private String ciphertext;  // base64(RSA-encrypted AES key || IV || AES-GCM ciphertext)

    public MeshPacket() {}

    public String getPacketId()              { return packetId; }
    public void   setPacketId(String p)      { this.packetId = p; }

    public int  getTtl()                     { return ttl; }
    public void setTtl(int ttl)              { this.ttl = ttl; }

    public Long getCreatedAt()               { return createdAt; }
    public void setCreatedAt(Long c)         { this.createdAt = c; }

    public String getCiphertext()            { return ciphertext; }
    public void   setCiphertext(String c)    { this.ciphertext = c; }
}

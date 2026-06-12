package com.demo.upimesh.model;

import jakarta.persistence.*;

/**
 * Stores the RSA keypair in the database so it survives Railway container restarts.
 * One row, id=1, always.
 */
@Entity
@Table(name = "server_keys")
public class ServerKeyEntity {

    @Id
    private Long id = 1L;

    @Column(nullable = false, length = 4096)
    private String privateKeyBase64;

    @Column(nullable = false, length = 4096)
    private String publicKeyBase64;

    public ServerKeyEntity() {}

    public ServerKeyEntity(String privateKeyBase64, String publicKeyBase64) {
        this.id = 1L;
        this.privateKeyBase64 = privateKeyBase64;
        this.publicKeyBase64  = publicKeyBase64;
    }

    public Long   getId()                              { return id; }
    public String getPrivateKeyBase64()                { return privateKeyBase64; }
    public void   setPrivateKeyBase64(String k)        { this.privateKeyBase64 = k; }
    public String getPublicKeyBase64()                 { return publicKeyBase64; }
    public void   setPublicKeyBase64(String k)         { this.publicKeyBase64 = k; }
}

package com.demo.upimesh.crypto;

import com.demo.upimesh.model.ServerKeyEntity;
import com.demo.upimesh.model.ServerKeyRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * RSA-2048 keypair stored in the DATABASE.
 *
 * Why DB instead of /tmp?
 *   Railway (and most cloud platforms) wipe /tmp on every container restart.
 *   Database rows survive restarts. So the keypair is stable across deployments.
 *
 * On first startup  → generates keypair, saves to DB (server_keys table, id=1)
 * On every restart  → loads keypair from DB, same keys every time
 *
 * Production upgrade path: swap DB storage for AWS KMS / HashiCorp Vault.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    @Autowired
    private ServerKeyRepository keyRepo;

    private KeyPair keyPair;

    @PostConstruct
    public void init() throws Exception {
        if (keyRepo.existsById(1L)) {
            keyPair = loadFromDb();
            log.info("RSA keypair loaded from database");
        } else {
            keyPair = generateAndSaveToDb();
            log.info("RSA-2048 keypair generated and saved to database");
        }
        log.info("Public key fingerprint: {}...", getPublicKeyBase64().substring(0, 32));
    }

    private KeyPair generateAndSaveToDb() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        String privB64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pubB64  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        keyRepo.save(new ServerKeyEntity(privB64, pubB64));
        return kp;
    }

    private KeyPair loadFromDb() throws Exception {
        ServerKeyEntity entity = keyRepo.findById(1L).orElseThrow();
        KeyFactory kf = KeyFactory.getInstance("RSA");

        byte[]     privBytes   = Base64.getDecoder().decode(entity.getPrivateKeyBase64());
        byte[]     pubBytes    = Base64.getDecoder().decode(entity.getPublicKeyBase64());
        PrivateKey privateKey  = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
        PublicKey  publicKey   = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
        return new KeyPair(publicKey, privateKey);
    }

    public PublicKey  getPublicKey()       { return keyPair.getPublic(); }
    public PrivateKey getPrivateKey()      { return keyPair.getPrivate(); }
    public String     getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}

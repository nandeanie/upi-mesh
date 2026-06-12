package com.demo.upimesh.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Hybrid encryption: RSA-OAEP (key transport) + AES-256-GCM (payload).
 *
 * Wire format (base64-encoded):
 *   [ 256 bytes — RSA-encrypted AES key ]
 *   [  12 bytes — GCM IV               ]
 *   [   N bytes — AES-GCM ciphertext + 16-byte auth tag ]
 *
 * AES-GCM is authenticated encryption: any single-bit flip in the ciphertext
 * causes decryption to throw AEADBadTagException, which is caught by
 * BridgeIngestionService and surfaced as INVALID. Intermediates cannot read
 * OR tamper with the payload.
 */
@Service
public class HybridCryptoService {

    private static final String RSA_ALGO          = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGO          = "AES/GCM/NoPadding";
    private static final int    AES_KEY_BITS      = 256;
    private static final int    GCM_IV_BYTES      = 12;
    private static final int    GCM_TAG_BITS      = 128;
    private static final int    RSA_KEY_BYTES     = 256;   // 2048-bit RSA → 256-byte output

    private final SecureRandom  rng  = new SecureRandom();
    private final ObjectMapper  json = new ObjectMapper();

    @Autowired private ServerKeyHolder serverKey;

    // ── Encrypt ──────────────────────────────────────────────────────────────

    /**
     * Encrypt a PaymentInstruction for delivery via the mesh.
     * Called by DemoService to simulate a sender phone.
     */
    public String encrypt(PaymentInstruction instruction, PublicKey recipientPublicKey) throws Exception {
        byte[] plaintext = json.writeValueAsBytes(instruction);

        // 1. One-time AES-256 key per packet
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(AES_KEY_BITS);
        SecretKey aesKey = kg.generateKey();

        // 2. AES-GCM encrypt
        byte[] iv = new byte[GCM_IV_BYTES];
        rng.nextBytes(iv);
        Cipher aes = Cipher.getInstance(AES_ALGO);
        aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] aesCiphertext = aes.doFinal(plaintext);

        // 3. RSA-OAEP wrap the AES key
        Cipher rsa = buildRsaCipher(Cipher.ENCRYPT_MODE, recipientPublicKey, null);
        byte[] encryptedAesKey = rsa.doFinal(aesKey.getEncoded());

        // 4. Pack
        ByteBuffer buf = ByteBuffer.allocate(encryptedAesKey.length + iv.length + aesCiphertext.length);
        buf.put(encryptedAesKey);
        buf.put(iv);
        buf.put(aesCiphertext);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    // ── Decrypt ──────────────────────────────────────────────────────────────

    /**
     * Decrypt a ciphertext blob using the server's private key.
     * Throws on tampered input, wrong key, or malformed blob.
     */
    public PaymentInstruction decrypt(String base64Ciphertext) throws Exception {
        byte[] all = Base64.getDecoder().decode(base64Ciphertext);

        int minLen = RSA_KEY_BYTES + GCM_IV_BYTES + (GCM_TAG_BITS / 8);
        if (all.length < minLen) {
            throw new IllegalArgumentException(
                    "Ciphertext too short (" + all.length + " < " + minLen + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(all);

        byte[] encryptedAesKey = new byte[RSA_KEY_BYTES];
        byte[] iv              = new byte[GCM_IV_BYTES];
        byte[] aesCiphertext   = new byte[all.length - RSA_KEY_BYTES - GCM_IV_BYTES];

        buf.get(encryptedAesKey);
        buf.get(iv);
        buf.get(aesCiphertext);

        // 1. RSA-OAEP unwrap
        Cipher rsa = buildRsaCipher(Cipher.DECRYPT_MODE, null, serverKey.getPrivateKey());
        byte[] aesKeyBytes = rsa.doFinal(encryptedAesKey);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // 2. AES-GCM decrypt + verify auth tag
        Cipher aes = Cipher.getInstance(AES_ALGO);
        aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = aes.doFinal(aesCiphertext);   // throws if tag fails

        return json.readValue(plaintext, PaymentInstruction.class);
    }

    // ── Hash ─────────────────────────────────────────────────────────────────

    /**
     * SHA-256 of the raw ciphertext bytes — used as the idempotency key.
     *
     * Hashing the ciphertext (not packetId) is critical:
     *  - packetId can be rewritten by any intermediate phone.
     *  - Two legitimate copies of the same payment have identical ciphertexts
     *    (deterministic for same key+IV+plaintext), so the same hash.
     *  - A tampered ciphertext has a different hash AND fails GCM tag check.
     */
    public String hashCiphertext(String base64Ciphertext) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(base64Ciphertext.getBytes());
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Cipher buildRsaCipher(int mode, PublicKey pub, java.security.PrivateKey priv)
            throws Exception {
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        Cipher rsa = Cipher.getInstance(RSA_ALGO);
        if (mode == Cipher.ENCRYPT_MODE) rsa.init(mode, pub,  oaep);
        else                             rsa.init(mode, priv, oaep);
        return rsa;
    }
}

package de.bwjobs.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM with the key from APP_STORAGE_KEY. Used for stored documents and stored secrets
 * (e.g. IMAP passwords). Without a configured key a development key is generated in the storage dir.
 */
@Component
public class Crypto {
    private static final Logger log = LoggerFactory.getLogger(Crypto.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKey key;

    public Crypto(AppProperties props) throws IOException {
        Path root = Path.of(props.storage().dir()).toAbsolutePath();
        Files.createDirectories(root);
        this.key = loadKey(props.storage().encryptionKey(), root);
    }

    private static SecretKey loadKey(String configured, Path root) throws IOException {
        byte[] raw;
        if (configured != null && !configured.isBlank()) {
            raw = Base64.getDecoder().decode(configured.trim());
        } else {
            Path devKey = root.resolve(".dev-storage-key");
            if (Files.exists(devKey)) {
                raw = Base64.getDecoder().decode(Files.readString(devKey).trim());
            } else {
                raw = new byte[32];
                RANDOM.nextBytes(raw);
                Files.writeString(devKey, Base64.getEncoder().encodeToString(raw));
            }
            log.warn("APP_STORAGE_KEY not set - using a generated development key in {}. Set APP_STORAGE_KEY in production.", devKey);
        }
        if (raw.length != 32) throw new IllegalStateException("APP_STORAGE_KEY must be 32 bytes (base64)");
        return new SecretKeySpec(raw, "AES");
    }

    /** Returns 12-byte IV + ciphertext. */
    public byte[] encrypt(byte[] data) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(data);
            return ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array();
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] all) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return c.doFinal(all, 12, all.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public String encryptString(String s) {
        return Base64.getEncoder().encodeToString(encrypt(s.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptString(String b64) {
        return new String(decrypt(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
    }
}

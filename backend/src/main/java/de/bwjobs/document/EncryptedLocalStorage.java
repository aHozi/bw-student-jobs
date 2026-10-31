package de.bwjobs.document;

import de.bwjobs.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Local disk storage with AES-256-GCM encryption at rest. File = 12-byte IV + ciphertext.
 * Key from APP_STORAGE_KEY (base64, 32 bytes). Without it a dev key is generated in the storage dir.
 */
@Component
public class EncryptedLocalStorage implements DocumentStorage {
    private static final Logger log = LoggerFactory.getLogger(EncryptedLocalStorage.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final SecretKey key;

    public EncryptedLocalStorage(AppProperties props) throws IOException {
        this.root = Path.of(props.storage().dir()).toAbsolutePath();
        Files.createDirectories(root);
        this.key = loadKey(props.storage().encryptionKey());
    }

    private SecretKey loadKey(String configured) throws IOException {
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

    @Override
    public String put(byte[] data) {
        String k = UUID.randomUUID().toString();
        write(k, data);
        return k;
    }

    @Override
    public void overwrite(String key, byte[] data) {
        write(key, data);
    }

    private void write(String k, byte[] data) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(data);
            Path p = path(k);
            Files.createDirectories(p.getParent());
            Files.write(p, ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public byte[] get(String k) {
        try {
            byte[] all = Files.readAllBytes(path(k));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return c.doFinal(all, 12, all.length - 12);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    @Override
    public void delete(String k) {
        try {
            Files.deleteIfExists(path(k));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String k) {
        if (!k.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid storage key");
        return root.resolve(k.substring(0, 2)).resolve(k);
    }
}

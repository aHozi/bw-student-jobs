package de.bwjobs.document;

import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Crypto;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Local disk storage with AES-256-GCM encryption at rest (see {@link Crypto}). */
@Component
public class EncryptedLocalStorage implements DocumentStorage {
    private final Path root;
    private final Crypto crypto;

    public EncryptedLocalStorage(AppProperties props, Crypto crypto) throws IOException {
        this.root = Path.of(props.storage().dir()).toAbsolutePath();
        Files.createDirectories(root);
        this.crypto = crypto;
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
            Path p = path(k);
            Files.createDirectories(p.getParent());
            Files.write(p, crypto.encrypt(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String k) {
        try {
            return crypto.decrypt(Files.readAllBytes(path(k)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

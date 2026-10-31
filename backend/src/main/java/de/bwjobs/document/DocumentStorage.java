package de.bwjobs.document;

/** Stores document bytes. Swap the implementation for S3/MinIO without touching callers. */
public interface DocumentStorage {
    String put(byte[] data);

    void overwrite(String key, byte[] data);

    byte[] get(String key);

    void delete(String key);
}

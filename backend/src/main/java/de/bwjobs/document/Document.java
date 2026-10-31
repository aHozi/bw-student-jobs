package de.bwjobs.document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

public record Document(
        long id, long userId, String kind, String title, String filename, String contentType,
        @JsonIgnore String storageKey, long sizeBytes, boolean original, Long derivedFromId, Long jobId,
        @JsonIgnore String extractedText, String contentJson, Instant createdAt, Instant updatedAt) {}

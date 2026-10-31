package de.bwjobs.company;

import java.time.Instant;

public record Company(
        long id, String name, String website, String careerUrl, String hqCity, String industry, String size,
        AtsType atsType, String atsTenant, String status, String statusMessage, String source,
        int scanIntervalHours, int consecutiveFailures, Instant lastScannedAt, Instant nextScanAt, Instant createdAt) {

    public Company withAts(AtsType type, String tenant, String careerUrl) {
        return new Company(id, name, website, careerUrl, hqCity, industry, size, type, tenant, status, statusMessage,
                source, scanIntervalHours, consecutiveFailures, lastScannedAt, nextScanAt, createdAt);
    }
}

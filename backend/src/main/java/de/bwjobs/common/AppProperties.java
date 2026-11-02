package de.bwjobs.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String jwtSecret,
        int jwtTtlHours,
        List<String> adminEmails,
        List<String> corsOrigins,
        Storage storage,
        Seed seed,
        Scan scan,
        Browser browser,
        Ai ai,
        Sources sources) {

    /** External job sources that replace career-page scanning (see app.sources in application.yml). */
    public record Sources(Arbeitsagentur arbeitsagentur, Adzuna adzuna, Email email) {}

    public record Arbeitsagentur(boolean enabled, String baseUrl, String apiKey, List<String> terms, List<String> places,
                                 int radiusKm, int publishedWithinDays, int maxDetailsPerRun) {}

    public record Adzuna(String appId, String appKey, String baseUrl, List<String> terms, String where,
                         int maxDaysOld, int maxPagesPerTerm) {
        public boolean enabled() {
            return appId != null && !appId.isBlank() && appKey != null && !appKey.isBlank();
        }
    }

    public record Email(boolean enabled, int pollMinutes, int firstSyncDays) {}

    public record Storage(String dir, String encryptionKey) {}

    public record Seed(boolean enabled) {}

    public record Scan(boolean enabled, int workers, int defaultIntervalHours, int minIntervalHours,
                       int maxIntervalHours, String userAgent, long perHostDelayMs,
                       int requestTimeoutSeconds, int maxDetailPagesPerCompany, boolean useBrowserFallback) {}

    public record Browser(String executablePath, boolean headless) {}

    public record Ai(String apiKey, String baseUrl, String model, int dailyLimitPerUser, boolean languageFallback) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}

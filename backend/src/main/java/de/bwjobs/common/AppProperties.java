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
        Ai ai) {

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

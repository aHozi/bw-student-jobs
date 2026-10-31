package de.bwjobs.job;

import java.time.Instant;
import java.util.List;

public record JobPosting(
        long id, long companyId, String externalId, String url, String applyUrl, String title,
        String descriptionHtml, String descriptionText, List<String> locations, boolean remote, Boolean inBw,
        List<String> studentTypes, List<String> itFields, List<String> techTerms,
        String germanLevel, String englishLevel, String postingLanguage, String languageSource, String contactEmail,
        Instant postedAt, Instant firstSeenAt, Instant lastSeenAt, String status, String fingerprint) {

    public String bestApplyUrl() {
        return applyUrl != null && !applyUrl.isBlank() ? applyUrl : url;
    }
}

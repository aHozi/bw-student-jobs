package de.bwjobs.source;

import java.time.Instant;
import java.util.List;

/** A job read from an external source (Bundesagentur, Adzuna, job-alert e-mail), before classification. */
public record ExternalJob(
        String source,          // ARBEITSAGENTUR / ADZUNA / EMAIL_ALERT
        String platform,        // ARBEITSAGENTUR / ADZUNA / LINKEDIN / STEPSTONE / INDEED
        String externalId,
        String title,
        String companyName,
        List<String> locations,
        String url,
        String applyUrl,
        String descriptionHtml,
        Instant postedAt,
        Long ownerUserId,       // set for e-mail jobs: only this user sees the job
        boolean snippetOnly) {}

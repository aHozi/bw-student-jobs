package de.bwjobs.scan;

import java.time.Instant;
import java.util.List;

/** A job as read from a career page, before classification. */
public record RawJob(
        String externalId,
        String title,
        String url,
        String applyUrl,
        List<String> locations,
        String descriptionHtml,
        Instant postedAt,
        boolean remote,
        boolean detailsLoaded) {

    public RawJob withDetails(String descriptionHtml, String applyUrl, List<String> locations, Instant postedAt) {
        return new RawJob(externalId, title, url,
                applyUrl != null ? applyUrl : this.applyUrl,
                locations != null && !locations.isEmpty() ? locations : this.locations,
                descriptionHtml, postedAt != null ? postedAt : this.postedAt, remote, true);
    }
}

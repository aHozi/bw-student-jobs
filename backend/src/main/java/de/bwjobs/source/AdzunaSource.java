package de.bwjobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Sql;
import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.FetchResult;
import de.bwjobs.scan.PoliteHttpClient;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Adzuna job search API for Germany (developer.adzuna.com): GET {base}/{page}?app_id&app_key&what&where.
 * Adzuna aggregates many German job boards; descriptions are short snippets.
 */
@Component
public class AdzunaSource {
    private static final int PER_PAGE = 50;

    private final PoliteHttpClient http;
    private final AppProperties.Adzuna cfg;

    public AdzunaSource(PoliteHttpClient http, AppProperties props) {
        this.http = http;
        this.cfg = props.sources().adzuna();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public List<ExternalJob> fetch() {
        Map<String, ExternalJob> byId = new LinkedHashMap<>();
        for (String term : cfg.terms()) {
            for (int page = 1; page <= cfg.maxPagesPerTerm(); page++) {
                String url = cfg.baseUrl() + "/" + page + "?app_id=" + enc(cfg.appId()) + "&app_key=" + enc(cfg.appKey())
                        + "&what=" + enc(term) + "&where=" + enc(cfg.where()) + "&max_days_old=" + cfg.maxDaysOld()
                        + "&results_per_page=" + PER_PAGE + "&content-type=application/json";
                FetchResult r = http.get(url, Map.of("Accept", "application/json"));
                if (!r.ok()) throw new FetchException("Adzuna HTTP " + r.status(), r.status());
                JsonNode results;
                try {
                    results = Sql.JSON.readTree(r.body()).path("results");
                } catch (Exception e) {
                    throw new FetchException("Invalid JSON from Adzuna", e);
                }
                for (JsonNode j : results) {
                    String id = j.path("id").asText(null);
                    if (id == null || byId.containsKey(id)) continue;
                    String location = j.path("location").path("display_name").asText(null);
                    String redirect = j.path("redirect_url").asText(null);
                    byId.put(id, new ExternalJob("ADZUNA", "ADZUNA", id, plain(j.path("title").asText(null)),
                            j.path("company").path("display_name").asText(null), location == null ? List.of() : List.of(location),
                            redirect, redirect, j.path("description").asText(null), instant(j.path("created").asText(null)), null, true));
                }
                if (results.size() < PER_PAGE) break;
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static String plain(String s) {
        return s == null ? null : Jsoup.parse(s).text();
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}

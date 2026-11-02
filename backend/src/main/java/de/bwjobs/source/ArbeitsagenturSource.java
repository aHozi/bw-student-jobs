package de.bwjobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Sql;
import de.bwjobs.job.StudentTypeRules;
import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.FetchResult;
import de.bwjobs.scan.PoliteHttpClient;
import org.jsoup.nodes.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Bundesagentur für Arbeit Jobsuche API (https://jobsuche.api.bund.dev, spec: github.com/bundesAPI/jobsuche-api).
 * Search: GET /pc/v6/jobs (header X-API-Key). Details: GET /pc/v4/jobdetails/{base64(refnr)}.
 * Searches student terms around BW cities; the full description comes from the detail call.
 */
@Component
public class ArbeitsagenturSource {
    private static final Logger log = LoggerFactory.getLogger(ArbeitsagenturSource.class);
    public static final String JOB_PAGE = "https://www.arbeitsagentur.de/jobsuche/jobdetail/";

    private final PoliteHttpClient http;
    private final AppProperties.Arbeitsagentur cfg;

    public ArbeitsagenturSource(PoliteHttpClient http, AppProperties props) {
        this.http = http;
        this.cfg = props.sources().arbeitsagentur();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    record Hit(String refnr, String title, String employer, List<String> locations, Instant published, String externalUrl) {}

    public List<ExternalJob> fetch() {
        Map<String, Hit> hits = new LinkedHashMap<>();
        for (String term : cfg.terms()) {
            for (String place : cfg.places()) {
                String url = cfg.baseUrl() + "/pc/v6/jobs?was=" + enc(term) + "&wo=" + enc(place) + "&umkreis=" + cfg.radiusKm()
                        + "&veroeffentlichtseit=" + cfg.publishedWithinDays() + "&page=1&size=100"
                        + (term.toLowerCase(Locale.ROOT).startsWith("praktikum") ? "&angebotsart=34" : "");
                JsonNode root = get(url);
                for (JsonNode j : root.path("stellenangebote")) {
                    String refnr = text(j, "refnr");
                    if (refnr == null || hits.containsKey(refnr)) continue;
                    JsonNode ort = j.path("arbeitsort");
                    hits.put(refnr, new Hit(refnr, firstNonBlank(text(j, "titel"), text(j, "stellenangebotsTitel")), text(j, "arbeitgeber"),
                            location(ort), date(text(j, "aktuelleVeroeffentlichungsdatum")), text(j, "externeUrl")));
                }
            }
        }
        List<ExternalJob> out = new ArrayList<>();
        int details = 0;
        for (Hit h : hits.values()) {
            // When the search already returns a title, skip non-student jobs before the detail call.
            if (h.title() != null && !StudentTypeRules.looksLikeStudentJob(h.title())) continue;
            if (details++ >= cfg.maxDetailsPerRun()) break;
            try {
                out.add(withDetails(h));
            } catch (FetchException e) {
                log.debug("BA details failed for {}: {}", h.refnr(), e.getMessage());
            }
        }
        log.info("Bundesagentur: {} search hits, {} detail calls", hits.size(), Math.min(details, hits.size()));
        return out;
    }

    ExternalJob withDetails(Hit h) {
        String code = Base64.getEncoder().encodeToString(h.refnr().getBytes(StandardCharsets.UTF_8));
        JsonNode d = get(cfg.baseUrl() + "/pc/v4/jobdetails/" + enc(code));
        String title = firstNonBlank(text(d, "stellenangebotsTitel"), text(d, "titel"), h.title(), text(d, "beruf"));
        String description = firstNonBlank(text(d, "stellenangebotsBeschreibung"), text(d, "stellenbeschreibung"));
        String employer = firstNonBlank(text(d, "arbeitgeber"), h.employer());
        List<String> locations = h.locations();
        if (locations.isEmpty()) {
            List<String> fromDetail = new ArrayList<>();
            for (JsonNode o : d.path("arbeitsorte")) fromDetail.addAll(location(o));
            locations = fromDetail;
        }
        String page = JOB_PAGE + h.refnr();
        String apply = h.externalUrl() != null && !h.externalUrl().isBlank() ? h.externalUrl() : page;
        return new ExternalJob("ARBEITSAGENTUR", "ARBEITSAGENTUR", h.refnr(), title, employer, locations, page, apply,
                toHtml(description), h.published(), null, false);
    }

    private JsonNode get(String url) {
        FetchResult r = http.get(url, Map.of("X-API-Key", cfg.apiKey(), "Accept", "application/json"));
        if (!r.ok()) throw new FetchException("Bundesagentur HTTP " + r.status() + " for " + url, r.status());
        try {
            return Sql.JSON.readTree(r.body());
        } catch (Exception e) {
            throw new FetchException("Invalid JSON from Bundesagentur", e);
        }
    }

    static List<String> location(JsonNode ort) {
        if (ort == null || ort.isMissingNode() || ort.isNull()) return List.of();
        String plz = ort.path("plz").isMissingNode() || ort.path("plz").isNull() ? null : ort.path("plz").asText();
        String s = String.join(" ", nonBlank(plz, text(ort, "ort")));
        String region = text(ort, "region");
        if (region != null && !region.isBlank()) s = s.isBlank() ? region : s + ", " + region;
        return s.isBlank() ? List.of() : List.of(s);
    }

    static String toHtml(String plain) {
        if (plain == null) return null;
        return "<p>" + Entities.escape(plain).replaceAll("\\r?\\n\\s*\\r?\\n", "</p><p>").replaceAll("\\r?\\n", "<br>") + "</p>";
    }

    private static Instant date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s.substring(0, 10)).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

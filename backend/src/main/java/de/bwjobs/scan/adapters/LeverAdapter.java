package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/** Lever Postings API: api.lever.co/v0/postings/{company}?mode=json (EU: api.eu.lever.co). */
@Component
public class LeverAdapter implements JobSourceAdapter {
    static String apiBase = "https://api.lever.co";
    static String euApiBase = "https://api.eu.lever.co";
    private final PoliteHttpClient http;

    public LeverAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.LEVER);
    }

    @Override
    public List<RawJob> list(Company c) {
        String tenant = c.atsTenant();
        String base = apiBase;
        if (tenant.startsWith("eu/")) {
            base = euApiBase;
            tenant = tenant.substring(3);
        }
        JsonNode root = Json.parse(http.get(base + "/v0/postings/" + tenant + "?mode=json", Map.of("Accept", "application/json")));
        List<RawJob> out = new ArrayList<>();
        for (JsonNode p : root) {
            StringBuilder html = new StringBuilder();
            String desc = Json.text(p, "description");
            if (desc != null) html.append(desc);
            for (JsonNode l : p.path("lists")) {
                html.append("<h3>").append(Json.text(l, "text")).append("</h3><ul>").append(Json.text(l, "content")).append("</ul>");
            }
            String additional = Json.text(p, "additional");
            if (additional != null) html.append(additional);
            JsonNode cat = p.path("categories");
            List<String> locs = new ArrayList<>(Json.nonBlank(Json.text(cat, "location")));
            for (JsonNode l : cat.path("allLocations")) if (!locs.contains(l.asText())) locs.add(l.asText());
            long created = p.path("createdAt").asLong(0);
            String workplace = Json.text(p, "workplaceType");
            out.add(new RawJob(Json.text(p, "id"), Json.text(p, "text"), Json.text(p, "hostedUrl"), Json.text(p, "applyUrl"),
                    locs, html.toString(), created > 0 ? Instant.ofEpochMilli(created) : null,
                    "remote".equalsIgnoreCase(workplace), true));
        }
        return out;
    }
}

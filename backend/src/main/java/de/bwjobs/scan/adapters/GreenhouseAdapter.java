package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.*;

/** Greenhouse Job Board API: boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true */
@Component
public class GreenhouseAdapter implements JobSourceAdapter {
    static String apiBase = "https://boards-api.greenhouse.io";
    private final PoliteHttpClient http;

    public GreenhouseAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.GREENHOUSE);
    }

    @Override
    public List<RawJob> list(Company c) {
        JsonNode root = Json.parse(http.get(apiBase + "/v1/boards/" + c.atsTenant() + "/jobs?content=true",
                Map.of("Accept", "application/json")));
        List<RawJob> out = new ArrayList<>();
        for (JsonNode j : root.path("jobs")) {
            String content = Json.text(j, "content");
            // Greenhouse returns HTML entity-encoded content
            String html = content == null ? null : Parser.unescapeEntities(content, false);
            String location = Json.text(j.path("location"), "name");
            List<String> locs = new ArrayList<>(Json.nonBlank(location));
            for (JsonNode o : j.path("offices")) {
                String n = Json.text(o, "location");
                if (n != null && !locs.contains(n)) locs.add(n);
            }
            String url = Json.text(j, "absolute_url");
            out.add(new RawJob(Json.text(j, "id"), Json.text(j, "title"), url, url, locs, html,
                    Json.instant(Json.text(j, "first_published") != null ? Json.text(j, "first_published") : Json.text(j, "updated_at")),
                    location != null && location.toLowerCase().contains("remote"), html != null));
        }
        return out;
    }
}

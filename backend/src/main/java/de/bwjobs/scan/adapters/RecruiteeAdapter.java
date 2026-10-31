package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.springframework.stereotype.Component;

import java.util.*;

/** Recruitee careers API: {tenant}.recruitee.com/api/offers/ */
@Component
public class RecruiteeAdapter implements JobSourceAdapter {
    static String scheme = "https://";
    static String hostSuffix = ".recruitee.com";
    private final PoliteHttpClient http;

    public RecruiteeAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.RECRUITEE);
    }

    @Override
    public List<RawJob> list(Company c) {
        JsonNode root = Json.parse(http.get(scheme + c.atsTenant() + hostSuffix + "/api/offers/", Map.of("Accept", "application/json")));
        List<RawJob> out = new ArrayList<>();
        for (JsonNode o : root.path("offers")) {
            String html = Optional.ofNullable(Json.text(o, "description")).orElse("")
                    + Optional.ofNullable(Json.text(o, "requirements")).map(r -> "<h3>Requirements</h3>" + r).orElse("");
            List<String> locs = Json.nonBlank(Json.text(o, "location"));
            out.add(new RawJob(Json.text(o, "id"), Json.text(o, "title"), Json.text(o, "careers_url"),
                    Json.text(o, "careers_apply_url"), locs, html, Json.instant(Json.text(o, "published_at")),
                    o.path("remote").asBoolean(false), true));
        }
        return out;
    }
}

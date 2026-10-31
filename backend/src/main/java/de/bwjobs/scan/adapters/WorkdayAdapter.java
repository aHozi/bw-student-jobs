package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Workday CXS JSON API used by myworkdayjobs.com career sites.
 * Tenant format: "host|tenant|site", e.g. "acme.wd3.myworkdayjobs.com|acme|Acme_Careers".
 */
@Component
public class WorkdayAdapter implements JobSourceAdapter {
    static String scheme = "https://";
    private final PoliteHttpClient http;

    public WorkdayAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.WORKDAY);
    }

    record Tenant(String host, String tenant, String site) {
        static Tenant parse(String s) {
            String[] p = s.split("\\|");
            if (p.length != 3) throw new IllegalArgumentException("Workday tenant must be host|tenant|site: " + s);
            return new Tenant(p[0], p[1], p[2]);
        }

        String api() {
            return scheme + host + "/wday/cxs/" + tenant + "/" + site;
        }
    }

    @Override
    public List<RawJob> list(Company c) {
        Tenant t = Tenant.parse(c.atsTenant());
        Map<String, RawJob> byPath = new LinkedHashMap<>();
        for (String term : STUDENT_SEARCH_TERMS) {
            int offset = 0;
            while (offset < 400) {
                Map<String, Object> body = Map.of("appliedFacets", Map.of(), "limit", 20, "offset", offset, "searchText", term);
                JsonNode root = Json.parse(http.postJson(t.api() + "/jobs", Sql.toJson(body)));
                JsonNode postings = root.path("jobPostings");
                for (JsonNode p : postings) {
                    String path = Json.text(p, "externalPath");
                    if (path == null || byPath.containsKey(path)) continue;
                    String id = p.path("bulletFields").isArray() && !p.path("bulletFields").isEmpty()
                            ? p.path("bulletFields").get(0).asText() : path;
                    byPath.put(path, new RawJob(id, Json.text(p, "title"), scheme + t.host() + "/" + t.site() + path, null,
                            Json.nonBlank(Json.text(p, "locationsText")), null, null,
                            Optional.ofNullable(Json.text(p, "remoteType")).orElse("").toLowerCase().contains("remote"), false));
                }
                int total = root.path("total").asInt(0);
                offset += 20;
                if (postings.size() < 20 || offset >= total) break;
            }
        }
        return new ArrayList<>(byPath.values());
    }

    @Override
    public RawJob details(Company c, RawJob job) {
        Tenant t = Tenant.parse(c.atsTenant());
        String path = job.url().substring((scheme + t.host() + "/" + t.site()).length());
        JsonNode info = Json.parse(http.get(t.api() + path, Map.of("Accept", "application/json"))).path("jobPostingInfo");
        List<String> locs = new ArrayList<>(Json.nonBlank(Json.text(info, "location")));
        for (JsonNode l : info.path("additionalLocations")) if (!locs.contains(l.asText())) locs.add(l.asText());
        String external = Json.text(info, "externalUrl");
        RawJob d = job.withDetails(Json.text(info, "jobDescription"), external, locs, Json.instant(Json.text(info, "startDate")));
        return external != null ? new RawJob(d.externalId(), d.title(), external, external, d.locations(), d.descriptionHtml(),
                d.postedAt(), d.remote(), true) : d;
    }
}

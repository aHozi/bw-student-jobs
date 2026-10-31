package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SmartRecruiters public Posting API: api.smartrecruiters.com/v1/companies/{id}/postings */
@Component
public class SmartRecruitersAdapter implements JobSourceAdapter {
    static String apiBase = "https://api.smartrecruiters.com";
    private final PoliteHttpClient http;

    public SmartRecruitersAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.SMARTRECRUITERS);
    }

    @Override
    public List<RawJob> list(Company c) {
        String tenant = c.atsTenant();
        Map<String, RawJob> byId = new LinkedHashMap<>();
        // Large tenants (e.g. Bosch) have thousands of postings: search by student keywords, Germany only.
        for (String term : STUDENT_SEARCH_TERMS) {
            int offset = 0;
            while (offset < 1000) {
                String url = apiBase + "/v1/companies/" + tenant + "/postings?limit=100&offset=" + offset
                        + "&country=de&q=" + URLEncoder.encode(term, StandardCharsets.UTF_8);
                JsonNode root = Json.parse(http.get(url, Map.of("Accept", "application/json")));
                JsonNode content = root.path("content");
                for (JsonNode p : content) {
                    String id = Json.text(p, "id");
                    if (id == null || byId.containsKey(id)) continue;
                    JsonNode loc = p.path("location");
                    String location = String.join(", ", Json.nonBlank(Json.text(loc, "city"), Json.text(loc, "region"), Json.text(loc, "country")));
                    boolean remote = loc.path("remote").asBoolean(false);
                    byId.put(id, new RawJob(id, Json.text(p, "name"),
                            "https://jobs.smartrecruiters.com/" + tenant + "/" + id, null,
                            location.isBlank() ? List.of() : List.of(location), null,
                            Json.instant(Json.text(p, "releasedDate")), remote, false));
                }
                int total = root.path("totalFound").asInt(0);
                offset += 100;
                if (content.size() < 100 || offset >= total) break;
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public RawJob details(Company c, RawJob job) {
        JsonNode p = Json.parse(http.get(apiBase + "/v1/companies/" + c.atsTenant() + "/postings/" + job.externalId(),
                Map.of("Accept", "application/json")));
        JsonNode sections = p.path("jobAd").path("sections");
        StringBuilder html = new StringBuilder();
        for (String s : List.of("companyDescription", "jobDescription", "qualifications", "additionalInformation")) {
            JsonNode sec = sections.path(s);
            if (sec.isMissingNode()) continue;
            String title = Json.text(sec, "title");
            String text = Json.text(sec, "text");
            if (text == null) continue;
            if (title != null) html.append("<h3>").append(title).append("</h3>");
            html.append(text);
        }
        String applyUrl = Json.text(p, "applyUrl");
        String postingUrl = Json.text(p, "postingUrl");
        RawJob updated = job.withDetails(html.toString(), applyUrl != null ? applyUrl : postingUrl, null, null);
        return postingUrl != null
                ? new RawJob(updated.externalId(), updated.title(), postingUrl, updated.applyUrl(), updated.locations(),
                updated.descriptionHtml(), updated.postedAt(), updated.remote(), true)
                : updated;
    }
}

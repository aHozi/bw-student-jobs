package de.bwjobs.scan.adapters;

import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.FetchResult;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.*;

/** Personio XML feed: {tenant}.jobs.personio.de/xml */
@Component
public class PersonioAdapter implements JobSourceAdapter {
    static String scheme = "https://";
    private final PoliteHttpClient http;

    public PersonioAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.PERSONIO);
    }

    @Override
    public List<RawJob> list(Company c) {
        String host = c.atsTenant().contains(".") ? c.atsTenant() : c.atsTenant() + ".jobs.personio.de";
        FetchResult r = http.get(scheme + host + "/xml?language=de", Map.of("Accept", "application/xml"));
        if (!r.ok()) throw new FetchException("HTTP " + r.status() + " for Personio feed " + host, r.status());
        Document xml = Jsoup.parse(r.body(), "", Parser.xmlParser());
        List<RawJob> out = new ArrayList<>();
        for (Element pos : xml.select("position")) {
            String id = childText(pos, "id");
            StringBuilder html = new StringBuilder();
            for (Element d : pos.select("jobDescriptions > jobDescription")) {
                String name = childText(d, "name");
                String value = childText(d, "value");
                if (name != null) html.append("<h3>").append(name).append("</h3>");
                if (value != null) html.append(value);
            }
            List<String> locs = new ArrayList<>(Json.nonBlank(childText(pos, "office")));
            for (Element o : pos.select("additionalOffices > office")) if (!locs.contains(o.text())) locs.add(o.text());
            String url = scheme + host + "/job/" + id;
            String schedule = childText(pos, "schedule");
            String employment = childText(pos, "employmentType");
            String title = childText(pos, "name");
            // Personio has explicit employment types like "intern" / "working_student"; add them as a hint to the title-based rules.
            String meta = String.join(" ", Json.nonBlank(employment, schedule, childText(pos, "seniority")));
            if (!meta.isBlank()) html.insert(0, "<p>" + meta + "</p>");
            out.add(new RawJob(id, title, url, url + "#apply", locs, html.toString(),
                    Json.instant(childText(pos, "createdAt")), false, true));
        }
        return out;
    }

    private static String childText(Element parent, String tag) {
        Element e = parent.selectFirst("> " + tag);
        // wholeText keeps CDATA HTML (job description values) as-is
        if (e == null) return null;
        String t = e.wholeText().trim();
        return t.isEmpty() ? null : t;
    }
}

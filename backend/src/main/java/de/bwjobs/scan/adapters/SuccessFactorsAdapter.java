package de.bwjobs.scan.adapters;

import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.FetchResult;
import de.bwjobs.scan.JobSourceAdapter;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SAP SuccessFactors Career Site Builder (e.g. jobs.sap.com): server-rendered search pages
 * at {base}/search/?q=..., with result links "a.jobTitle-link". Tenant = base URL.
 */
@Component
public class SuccessFactorsAdapter implements JobSourceAdapter {
    private final PoliteHttpClient http;

    public SuccessFactorsAdapter(PoliteHttpClient http) {
        this.http = http;
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.SUCCESSFACTORS);
    }

    @Override
    public List<RawJob> list(Company c) {
        String base = c.atsTenant() != null ? c.atsTenant() : c.careerUrl();
        Map<String, RawJob> byUrl = new LinkedHashMap<>();
        for (String term : STUDENT_SEARCH_TERMS) {
            for (int startrow = 0; startrow < 125; startrow += 25) {
                String url = base + "/search/?q=" + URLEncoder.encode(term, StandardCharsets.UTF_8) + "&startrow=" + startrow;
                FetchResult r = http.get(url);
                if (!r.ok()) break;
                Document doc = Jsoup.parse(r.body(), r.finalUrl());
                int found = 0;
                for (Element a : doc.select("a.jobTitle-link")) {
                    String href = a.absUrl("href");
                    if (href.isBlank() || byUrl.containsKey(href)) continue;
                    found++;
                    Element row = a.closest("tr, li, .data-row, .job-tile");
                    String location = null;
                    if (row != null) {
                        Element l = row.selectFirst(".jobLocation, [data-careersite-propertyid=location], .location");
                        if (l != null) location = l.text();
                    }
                    String id = href.replaceAll(".*/(\\d+)/?$", "$1");
                    byUrl.put(href, new RawJob(id, a.text(), href, null, Json.nonBlank(location), null, null, false, false));
                }
                if (found == 0 || doc.select("a.jobTitle-link").size() < 25) break;
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    @Override
    public RawJob details(Company c, RawJob job) {
        FetchResult r = http.get(job.url());
        if (!r.ok()) return job;
        Document doc = Jsoup.parse(r.body(), r.finalUrl());
        RawJob d = DetailExtractor.enrich(job, doc);
        Element date = doc.selectFirst("[itemprop=datePosted], [data-careersite-propertyid=date]");
        if (date != null && d.postedAt() == null) {
            String v = date.hasAttr("content") ? date.attr("content") : date.text();
            d = d.withDetails(d.descriptionHtml(), d.applyUrl(), d.locations(), Json.instant(v));
        }
        return d;
    }
}

package de.bwjobs.scan.adapters;

import de.bwjobs.common.AppProperties;
import de.bwjobs.company.AggregatorGuard;
import de.bwjobs.company.AtsDetector;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.job.StudentTypeRules;
import de.bwjobs.scan.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scanner for any official career page without a known API (custom sites, Softgarden, d.vinci, JOIN, ...).
 * Strategy: JSON-LD JobPostings → student-looking job links → one level of listing links → pagination
 * → headless browser rendering if the static HTML has no jobs.
 */
@Component
public class GenericCareerPageAdapter implements JobSourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(GenericCareerPageAdapter.class);

    private static final Pattern LISTING_TEXT = Pattern.compile(
            "(?i)stellenangebote|offene stellen|alle jobs|alle stellen|jobb(?:ö|oe)rse|job ?search|jobsuche|jobs finden|current openings|open positions|view (?:all )?jobs|zu den jobs|stellenb(?:ö|oe)rse|studierende|students|studenten|praktik|werkstudent|absolvent|einstieg");
    private static final Pattern NEXT_TEXT = Pattern.compile("(?i)^(?:weiter|next|nächste|naechste|»|›|>)$|nächste seite|next page");

    private final PoliteHttpClient http;
    private final BrowserService browser;
    private final AggregatorGuard guard;
    private final AppProperties.Scan cfg;

    public GenericCareerPageAdapter(PoliteHttpClient http, BrowserService browser, AggregatorGuard guard, AppProperties props) {
        this.http = http;
        this.browser = browser;
        this.guard = guard;
        this.cfg = props.scan();
    }

    @Override
    public Set<AtsType> supports() {
        return EnumSet.of(AtsType.GENERIC, AtsType.SOFTGARDEN, AtsType.DVINCI, AtsType.JOIN);
    }

    @Override
    public List<RawJob> list(Company c) {
        if (c.careerUrl() == null) throw new FetchException("No career URL for " + c.name(), 0);
        Map<String, RawJob> jobs = new LinkedHashMap<>();
        Document start = fetch(c.careerUrl());
        if (start != null) collectWithPagination(start, c, jobs);

        if (jobs.isEmpty() && start != null) {
            for (String listing : listingLinks(start, c)) {
                Document d = fetch(listing);
                if (d != null) collectWithPagination(d, c, jobs);
                if (!jobs.isEmpty()) break;
            }
        }
        if (jobs.isEmpty() && cfg.useBrowserFallback()) {
            try {
                String html = browser.renderHtml(c.careerUrl());
                Document rendered = Jsoup.parse(html, c.careerUrl());
                collect(rendered, c, jobs);
            } catch (Exception e) {
                log.debug("Browser render failed for {}: {}", c.careerUrl(), e.getMessage());
            }
        }
        return new ArrayList<>(jobs.values());
    }

    @Override
    public RawJob details(Company c, RawJob job) {
        if (job.detailsLoaded()) return job;
        Document doc = fetch(job.url());
        if (doc == null) return job;
        if (DetailExtractor.textLength(doc) < 400 && cfg.useBrowserFallback()) {
            try {
                doc = Jsoup.parse(browser.renderHtml(job.url()), job.url());
            } catch (Exception e) {
                log.debug("Browser render failed for {}: {}", job.url(), e.getMessage());
            }
        }
        return DetailExtractor.enrich(job, doc);
    }

    private void collectWithPagination(Document first, Company c, Map<String, RawJob> jobs) {
        Document page = first;
        Set<String> seenPages = new HashSet<>();
        for (int i = 0; i < 5 && page != null; i++) {
            seenPages.add(page.location());
            int before = jobs.size();
            collect(page, c, jobs);
            if (jobs.size() == before && i > 0) break;
            String next = nextPageLink(page);
            if (next == null || seenPages.contains(next) || !sameSiteOrAts(next, c)) break;
            page = fetch(next);
        }
    }

    void collect(Document doc, Company c, Map<String, RawJob> jobs) {
        for (RawJob j : JsonLdJobs.extract(doc, doc.location())) {
            if (j.title() != null && !guard.isAggregator(j.url())) jobs.putIfAbsent(j.url(), j);
        }
        for (Element a : doc.select("a[href]")) {
            String text = a.text().trim();
            if (text.isEmpty()) text = a.attr("title").trim();
            if (text.length() < 8 || text.length() > 200) continue;
            if (!StudentTypeRules.looksLikeStudentJob(text)) continue;
            String href = stripFragment(a.absUrl("href"));
            if (href.isBlank() || href.startsWith("mailto:") || href.equals(doc.location())) continue;
            if (!sameSiteOrAts(href, c)) continue;
            String location = nearbyLocation(a);
            jobs.putIfAbsent(href, new RawJob(href, cleanTitle(text), href, null, Json.nonBlank(location), null, null, false, false));
        }
    }

    private List<String> listingLinks(Document doc, Company c) {
        List<String> out = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            if (!LISTING_TEXT.matcher(a.text()).find()) continue;
            String href = stripFragment(a.absUrl("href"));
            if (href.isBlank() || href.equals(doc.location()) || !sameSiteOrAts(href, c)) continue;
            if (!out.contains(href)) out.add(href);
            if (out.size() >= 3) break;
        }
        return out;
    }

    private String nextPageLink(Document doc) {
        Element rel = doc.selectFirst("a[rel=next], link[rel=next]");
        if (rel != null) return rel.absUrl("href");
        for (Element a : doc.select("a[href]")) {
            String label = a.text().trim().isEmpty() ? a.attr("aria-label") : a.text().trim();
            if (NEXT_TEXT.matcher(label).find()) return a.absUrl("href");
        }
        return null;
    }

    private static String nearbyLocation(Element a) {
        Element row = a.closest("li, tr, article, .job, [class*=job-item], [class*=jobitem], [class*=job-list-item]");
        if (row == null) return null;
        Element loc = row.selectFirst("[class*=location], [class*=standort], [class*=ort], [itemprop=jobLocation]");
        return loc == null || loc.text().length() > 150 ? null : loc.text();
    }

    private static String cleanTitle(String text) {
        return text.replaceAll("\\s+", " ").replaceAll("(?i)\\s*(mehr erfahren|details|jetzt bewerben|apply now)\\s*$", "").trim();
    }

    private boolean sameSiteOrAts(String url, Company c) {
        if (guard.isAggregator(url)) return false;
        if (AtsDetector.fromUrl(url).isPresent()) return true;
        String h = host(url);
        if (h == null) return false;
        for (String base : new String[]{c.careerUrl(), c.website()}) {
            String bh = host(base);
            if (bh != null && registrable(h).equals(registrable(bh))) return true;
        }
        return false;
    }

    private Document fetch(String url) {
        try {
            FetchResult r = http.get(url);
            if (!r.ok() || guard.isAggregator(r.finalUrl())) return null;
            return Jsoup.parse(r.body(), r.finalUrl());
        } catch (FetchException e) {
            log.debug("Fetch failed {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String host(String url) {
        try {
            return url == null ? null : URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String registrable(String host) {
        String[] p = host.toLowerCase().split("\\.");
        return p.length <= 2 ? host.toLowerCase() : p[p.length - 2] + "." + p[p.length - 1];
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }
}

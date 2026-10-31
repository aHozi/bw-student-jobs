package de.bwjobs.company;

import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.FetchResult;
import de.bwjobs.scan.PoliteHttpClient;
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
 * Finds a company's official career page starting from its website, and detects the ATS behind it.
 * Only follows links on the company's own domain or known ATS domains; never aggregators.
 */
@Component
public class CareerPageDiscovery {
    private static final Logger log = LoggerFactory.getLogger(CareerPageDiscovery.class);

    private static final Pattern CAREER_TEXT = Pattern.compile(
            "(?i)karriere|career|jobs?\\b|stellen|offene positionen|arbeiten bei|work with us|join us|join our team|jobboerse|jobbörse|einstieg");
    private static final Pattern LISTING_TEXT = Pattern.compile(
            "(?i)stellenangebote|offene stellen|alle jobs|alle stellen|jobb(?:ö|oe)rse|job ?search|jobsuche|jobs finden|current openings|open positions|view jobs|zu den jobs|stellenbörse|studierende|students|studenten|praktik|werkstudent");
    private static final List<String> COMMON_PATHS = List.of(
            "/karriere", "/karriere/", "/de/karriere", "/de/karriere/", "/careers", "/en/careers", "/career",
            "/jobs", "/de/jobs", "/stellenangebote", "/unternehmen/karriere", "/de/unternehmen/karriere");

    private final PoliteHttpClient http;
    private final AggregatorGuard guard;

    public CareerPageDiscovery(PoliteHttpClient http, AggregatorGuard guard) {
        this.http = http;
        this.guard = guard;
    }

    public record Result(boolean found, String careerUrl, AtsType atsType, String tenant, String message) {}

    public Result discover(String website, String careerUrlHint) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (careerUrlHint != null && !careerUrlHint.isBlank()) {
            Optional<AtsDetector.Detection> direct = AtsDetector.fromUrl(careerUrlHint);
            if (direct.isPresent() && direct.get().type() != AtsType.SUCCESSFACTORS) {
                return new Result(true, careerUrlHint, direct.get().type(), direct.get().tenant(), "Detected from career URL");
            }
            candidates.add(careerUrlHint);
        }
        String base = website == null ? null : AtsDetector.baseUrl(normalizeUrl(website));
        if (base != null) {
            try {
                FetchResult home = http.get(base + "/");
                if (home.ok()) candidates.addAll(careerLinks(home.finalUrl(), home.body(), base));
            } catch (FetchException e) {
                log.debug("Homepage fetch failed for {}: {}", base, e.getMessage());
                if (e.status() == -1 && candidates.isEmpty()) {
                    return new Result(false, null, AtsType.UNKNOWN, null, "Website not reachable: " + e.getMessage());
                }
            }
            for (String p : COMMON_PATHS) candidates.add(base + p);
        }

        String firstWorking = null;
        int tried = 0;
        for (String url : candidates) {
            if (tried++ >= 10) break;
            if (guard.isAggregator(url)) continue;
            try {
                FetchResult page = http.get(url);
                if (!page.ok()) continue;
                if (guard.isAggregator(page.finalUrl())) continue;
                if (firstWorking == null) firstWorking = page.finalUrl();
                Optional<AtsDetector.Detection> d = AtsDetector.fromPage(page.finalUrl(), page.body());
                if (d.isPresent()) {
                    AtsDetector.Detection det = d.get();
                    String career = det.type() == AtsType.GENERIC || det.type() == AtsType.SUCCESSFACTORS ? page.finalUrl() : det.url();
                    return new Result(true, career, det.type(), det.tenant(), "Detected " + det.type() + " via " + page.finalUrl());
                }
                // One level deeper: "Stellenangebote" / "Job search" links from a career landing page
                for (String listing : listingLinks(page.finalUrl(), page.body(), base)) {
                    if (guard.isAggregator(listing)) continue;
                    Optional<AtsDetector.Detection> d2 = AtsDetector.fromUrl(listing);
                    if (d2.isPresent()) {
                        return new Result(true, listing, d2.get().type(), d2.get().tenant(), "Detected " + d2.get().type() + " via link");
                    }
                    try {
                        FetchResult lp = http.get(listing);
                        if (lp.ok() && !guard.isAggregator(lp.finalUrl())) {
                            Optional<AtsDetector.Detection> d3 = AtsDetector.fromPage(lp.finalUrl(), lp.body());
                            if (d3.isPresent()) {
                                AtsDetector.Detection det = d3.get();
                                String career = det.type() == AtsType.GENERIC || det.type() == AtsType.SUCCESSFACTORS ? lp.finalUrl() : det.url();
                                return new Result(true, career, det.type(), det.tenant(), "Detected " + det.type() + " via " + lp.finalUrl());
                            }
                        }
                    } catch (FetchException e) {
                        log.debug("Listing fetch failed {}: {}", listing, e.getMessage());
                    }
                    break; // only the best listing link
                }
            } catch (FetchException e) {
                log.debug("Candidate fetch failed {}: {}", url, e.getMessage());
            }
        }
        if (firstWorking != null) {
            return new Result(true, firstWorking, AtsType.GENERIC, null, "No known ATS found; using generic scanner on " + firstWorking);
        }
        return new Result(false, null, AtsType.UNKNOWN, null, "No reachable career page found");
    }

    private List<String> careerLinks(String pageUrl, String html, String base) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl);
        Map<String, Integer> scored = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank() || href.startsWith("mailto:")) continue;
            if (!sameSiteOrAts(href, base)) continue;
            int score = 0;
            if (CAREER_TEXT.matcher(a.text()).find()) score += 2;
            if (CAREER_TEXT.matcher(href).find()) score += 1;
            if (score > 0) scored.merge(stripFragment(href), score, Math::max);
        }
        return scored.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(5).map(Map.Entry::getKey).toList();
    }

    private List<String> listingLinks(String pageUrl, String html, String base) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl);
        List<String> out = new ArrayList<>();
        for (Element a : doc.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank() || href.equals(pageUrl)) continue;
            if (!sameSiteOrAts(href, base)) continue;
            if (LISTING_TEXT.matcher(a.text()).find() || AtsDetector.fromUrl(href).isPresent()) out.add(stripFragment(href));
        }
        // Links that point straight to an ATS first
        out.sort(Comparator.comparing(u -> AtsDetector.fromUrl(u).isPresent() ? 0 : 1));
        return out.stream().distinct().limit(3).toList();
    }

    private boolean sameSiteOrAts(String url, String base) {
        if (guard.isAggregator(url)) return false;
        if (AtsDetector.fromUrl(url).isPresent()) return true;
        String h = AggregatorGuard.host(url);
        String b = AggregatorGuard.host(base);
        if (h == null || b == null) return false;
        return registrable(h).equals(registrable(b));
    }

    static String registrable(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        if (Set.of("co.uk", "com.au", "co.jp").contains(last2) && parts.length >= 3) {
            return parts[parts.length - 3] + "." + last2;
        }
        return last2;
    }

    private static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    static String normalizeUrl(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        try {
            URI.create(u);
            return u;
        } catch (Exception e) {
            return null;
        }
    }
}

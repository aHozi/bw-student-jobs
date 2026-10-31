package de.bwjobs.company;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects which ATS hosts a company's official career page, from a URL or from the
 * page HTML (links, iframes, scripts). The tenant string is what the matching adapter needs.
 */
public final class AtsDetector {
    private AtsDetector() {}

    public record Detection(AtsType type, String tenant, String url) {}

    private static final Pattern WORKDAY = Pattern.compile("https?://([a-z0-9-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}-[A-Z]{2}/)?([A-Za-z0-9_-]+)");
    private static final Pattern SMARTRECRUITERS = Pattern.compile("https?://(?:jobs|careers)\\.smartrecruiters\\.com/([A-Za-z0-9_-]+)");
    private static final Pattern SMARTRECRUITERS_API = Pattern.compile("api\\.smartrecruiters\\.com/v1/companies/([A-Za-z0-9_-]+)");
    private static final Pattern GREENHOUSE = Pattern.compile("https?://(?:boards|job-boards)(?:\\.eu)?\\.greenhouse\\.io/(?:embed/job_board(?:/js)?\\?for=)?([A-Za-z0-9_-]+)");
    private static final Pattern GREENHOUSE_FOR = Pattern.compile("greenhouse\\.io/embed/job_board(?:/js)?\\?for=([A-Za-z0-9_-]+)");
    private static final Pattern LEVER = Pattern.compile("https?://jobs\\.(eu\\.)?lever\\.co/([A-Za-z0-9_-]+)");
    private static final Pattern PERSONIO = Pattern.compile("https?://([a-z0-9-]+)\\.jobs\\.personio\\.(de|com)");
    private static final Pattern RECRUITEE = Pattern.compile("https?://([a-z0-9-]+)\\.recruitee\\.com");
    private static final Pattern SOFTGARDEN = Pattern.compile("https?://([a-z0-9-]+)\\.softgarden\\.io");
    private static final Pattern DVINCI = Pattern.compile("https?://([a-z0-9-]+)\\.dvinci(?:-hr)?\\.(?:de|com)");
    private static final Pattern JOIN = Pattern.compile("https?://join\\.com/companies/([A-Za-z0-9_-]+)");
    private static final Pattern SUCCESSFACTORS = Pattern.compile(
            "https?://(career\\d*\\.(?:sap)?successfactors\\.(?:eu|com)|[a-z0-9.-]*sapsf\\.(?:eu|com)|jobs\\.sap\\.com)");

    /** Detects the ATS from a single URL. */
    public static Optional<Detection> fromUrl(String url) {
        if (url == null) return Optional.empty();
        Matcher m;
        if ((m = WORKDAY.matcher(url)).find()) {
            String host = m.group(1) + "." + m.group(2) + ".myworkdayjobs.com";
            return Optional.of(new Detection(AtsType.WORKDAY, host + "|" + m.group(1) + "|" + m.group(3), url));
        }
        if ((m = SMARTRECRUITERS.matcher(url)).find()) return Optional.of(new Detection(AtsType.SMARTRECRUITERS, m.group(1), url));
        if ((m = SMARTRECRUITERS_API.matcher(url)).find()) return Optional.of(new Detection(AtsType.SMARTRECRUITERS, m.group(1), url));
        if ((m = GREENHOUSE_FOR.matcher(url)).find()) return Optional.of(new Detection(AtsType.GREENHOUSE, m.group(1), url));
        if ((m = GREENHOUSE.matcher(url)).find() && !m.group(1).equals("embed")) return Optional.of(new Detection(AtsType.GREENHOUSE, m.group(1), url));
        if ((m = LEVER.matcher(url)).find()) {
            String tenant = (m.group(1) != null ? "eu/" : "") + m.group(2);
            return Optional.of(new Detection(AtsType.LEVER, tenant, url));
        }
        if ((m = PERSONIO.matcher(url)).find()) return Optional.of(new Detection(AtsType.PERSONIO, m.group(1) + ".jobs.personio." + m.group(2), url));
        if ((m = RECRUITEE.matcher(url)).find() && !m.group(1).equals("www")) return Optional.of(new Detection(AtsType.RECRUITEE, m.group(1), url));
        if ((m = SOFTGARDEN.matcher(url)).find()) return Optional.of(new Detection(AtsType.SOFTGARDEN, m.group(1), url));
        if ((m = DVINCI.matcher(url)).find()) return Optional.of(new Detection(AtsType.DVINCI, m.group(1), url));
        if ((m = JOIN.matcher(url)).find()) return Optional.of(new Detection(AtsType.JOIN, m.group(1), url));
        if ((m = SUCCESSFACTORS.matcher(url)).find()) return Optional.of(new Detection(AtsType.SUCCESSFACTORS, baseUrl(url), url));
        return Optional.empty();
    }

    /** Detects from page HTML: the page URL first, then links/iframes/scripts, then CSB markers. */
    public static Optional<Detection> fromPage(String pageUrl, String html) {
        Optional<Detection> direct = fromUrl(pageUrl);
        if (direct.isPresent()) return direct;
        if (html == null || html.isBlank()) return Optional.empty();
        Document doc = Jsoup.parse(html, pageUrl);
        List<String> candidates = new ArrayList<>();
        for (Element e : doc.select("a[href], iframe[src], script[src], link[href], form[action]")) {
            String u = e.hasAttr("href") ? e.absUrl("href") : e.hasAttr("src") ? e.absUrl("src") : e.absUrl("action");
            if (!u.isBlank()) candidates.add(u);
        }
        // Inline scripts sometimes configure embeds, e.g. Greenhouse "for=" or Personio host.
        for (Element s : doc.select("script:not([src])")) candidates.addAll(extractUrls(s.data()));
        for (String c : candidates) {
            Optional<Detection> d = fromUrl(c);
            if (d.isPresent() && d.get().type() != AtsType.SUCCESSFACTORS) return d;
        }
        // SAP SuccessFactors Career Site Builder pages are often on the company's own domain.
        if (html.contains("jobTitle-link") || html.contains("rmkcdn.successfactors.com") || html.contains("data-careersite-propertyid")) {
            return Optional.of(new Detection(AtsType.SUCCESSFACTORS, baseUrl(pageUrl), pageUrl));
        }
        for (String c : candidates) {
            Optional<Detection> d = fromUrl(c);
            if (d.isPresent()) return d;
        }
        if (html.contains("\"@type\":\"JobPosting\"") || html.contains("\"@type\": \"JobPosting\"")) {
            return Optional.of(new Detection(AtsType.GENERIC, null, pageUrl));
        }
        return Optional.empty();
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[A-Za-z0-9./?=_%&:#-]+");

    private static List<String> extractUrls(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = URL_IN_TEXT.matcher(text == null ? "" : text);
        while (m.find() && out.size() < 50) out.add(m.group());
        return out;
    }

    static String baseUrl(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return url;
        }
    }
}

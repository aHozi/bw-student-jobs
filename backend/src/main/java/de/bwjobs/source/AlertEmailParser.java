package de.bwjobs.source;

import de.bwjobs.job.HtmlText;
import de.bwjobs.job.StudentTypeRules;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts jobs from LinkedIn, StepStone and Indeed job-alert e-mails. Job links are recognised by their
 * URL pattern (also inside tracking redirects); title = link text, company/location = the nearby lines of the
 * same job card. Unknown layouts fall back to student-looking links on the platform's domain.
 */
public final class AlertEmailParser {
    private AlertEmailParser() {}

    public record ParsedJob(String platform, String externalId, String title, String company, String location, String url) {}

    private static final Pattern LINKEDIN = Pattern.compile("linkedin\\.com/(?:comm/)?jobs/view/(\\d+)");
    private static final Pattern STEPSTONE = Pattern.compile("stepstone\\.(?:de|at|com)/stellenangebote--([^\"?#&\\s]+?)-(\\d+)-inline\\.html");
    private static final Pattern INDEED = Pattern.compile("indeed\\.[a-z.]+/(?:rc/clk|viewjob|pagead/clk|m/viewjob|applystart|jobs)[^\"\\s]*?[?&](?:jk|vjk)=([0-9a-f]{8,})");
    private static final Pattern GENERIC_LINE = Pattern.compile(
            "(?i)^(?:view job|job ansehen|ansehen|details|mehr erfahren|jetzt bewerben|apply( now)?|easy apply|einfach bewerben|bewerben|" +
            "neu|new|promoted|gesponsert|sponsored|actively recruiting|aktiv auf der suche|be an early applicant|" +
            "see all jobs|alle jobs anzeigen|job alert.*|jobagent.*|unsubscribe|abmelden|.*vor \\d+ (?:tag|tagen|stunden?).*|" +
            ".*\\d+ (?:days?|hours?) ago.*|.*€.*|.*alumni.*|.*connections?.*|.*kontakte?.*)$");
    private static final Pattern SPLIT = Pattern.compile("\\s+[·•|–-]\\s+");

    public static String platformOf(String fromAddress) {
        if (fromAddress == null) return null;
        String f = fromAddress.toLowerCase(Locale.ROOT);
        if (f.contains("linkedin.")) return "LINKEDIN";
        if (f.contains("stepstone.")) return "STEPSTONE";
        if (f.contains("indeed.")) return "INDEED";
        return null;
    }

    public static List<ParsedJob> parse(String platform, String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        Map<String, List<Element>> anchorsById = new LinkedHashMap<>();
        Map<String, String> urlById = new HashMap<>();
        for (Element a : doc.select("a[href]")) {
            String href = decode(a.attr("href"));
            String[] idAndUrl = match(platform, href);
            if (idAndUrl == null) continue;
            anchorsById.computeIfAbsent(idAndUrl[0], k -> new ArrayList<>()).add(a);
            urlById.putIfAbsent(idAndUrl[0], idAndUrl[1]);
        }
        List<ParsedJob> out = new ArrayList<>();
        for (var e : anchorsById.entrySet()) {
            Element titleAnchor = e.getValue().stream()
                    .filter(a -> !a.text().isBlank() && !GENERIC_LINE.matcher(a.text().trim()).matches())
                    .max(Comparator.comparingInt(a -> a.text().length())).orElse(null);
            if (titleAnchor == null) continue;
            String title = clean(titleAnchor.text());
            String[] companyLocation = companyAndLocation(titleAnchor, title);
            out.add(new ParsedJob(platform, e.getKey(), title, companyLocation[0], companyLocation[1], urlById.get(e.getKey())));
        }
        if (out.isEmpty()) out.addAll(fallback(platform, doc));
        return out;
    }

    /** Returns {id, canonical url} for a job link of the given platform, or null. */
    static String[] match(String platform, String href) {
        Matcher m;
        switch (platform) {
            case "LINKEDIN" -> {
                if ((m = LINKEDIN.matcher(href)).find()) return new String[]{m.group(1), "https://www.linkedin.com/jobs/view/" + m.group(1) + "/"};
            }
            case "STEPSTONE" -> {
                if ((m = STEPSTONE.matcher(href)).find())
                    return new String[]{m.group(2), "https://www.stepstone.de/stellenangebote--" + m.group(1) + "-" + m.group(2) + "-inline.html"};
            }
            case "INDEED" -> {
                if ((m = INDEED.matcher(href)).find()) return new String[]{m.group(1), "https://de.indeed.com/viewjob?jk=" + m.group(1)};
            }
            default -> { }
        }
        return null;
    }

    /** Company = first informative line of the job card after the title, location = the next one. */
    private static String[] companyAndLocation(Element titleAnchor, String title) {
        Element card = titleAnchor;
        for (int i = 0; i < 6 && card.parent() != null; i++) {
            Element p = card.parent();
            if (p.text().length() > 700) break;
            card = p;
            if (informativeLines(card, title).size() >= 2) break;
        }
        List<String> lines = informativeLines(card, title);
        String company = null, location = null;
        for (String line : lines) {
            String[] parts = SPLIT.split(line);
            if (company == null && parts.length >= 2) {
                company = parts[0].trim();
                location = parts[1].trim();
                break;
            }
            if (company == null) company = line;
            else if (location == null) {
                location = line;
                break;
            }
        }
        return new String[]{company, location};
    }

    private static List<String> informativeLines(Element card, String title) {
        List<String> out = new ArrayList<>();
        for (String raw : HtmlText.toText(card.html()).split("\\n")) {
            String l = clean(raw.replace("•", ""));
            if (l.length() < 2 || l.length() > 120 || l.equalsIgnoreCase(title) || GENERIC_LINE.matcher(l).matches()) continue;
            if (!out.contains(l)) out.add(l);
        }
        return out;
    }

    private static List<ParsedJob> fallback(String platform, Document doc) {
        List<ParsedJob> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String domain = switch (platform) {
            case "LINKEDIN" -> "linkedin.";
            case "STEPSTONE" -> "stepstone.";
            case "INDEED" -> "indeed.";
            default -> "\u0000";
        };
        for (Element a : doc.select("a[href]")) {
            String text = clean(a.text());
            String href = decode(a.attr("href"));
            if (!href.contains(domain) || !StudentTypeRules.looksLikeStudentJob(text) || !seen.add(text)) continue;
            String[] cl = companyAndLocation(a, text);
            out.add(new ParsedJob(platform, Integer.toHexString(href.hashCode()), text, cl[0], cl[1], a.attr("href")));
        }
        return out;
    }

    /** Tracking links often wrap the real URL url-encoded; decode twice to reach it. */
    static String decode(String href) {
        String h = href;
        for (int i = 0; i < 2; i++) {
            try {
                h = URLDecoder.decode(h, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        return h;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }
}

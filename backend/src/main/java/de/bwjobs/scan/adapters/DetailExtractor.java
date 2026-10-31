package de.bwjobs.scan.adapters;

import de.bwjobs.scan.RawJob;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;
import java.util.regex.Pattern;

/** Extracts description, locations and apply link from a job detail page (any career site). */
final class DetailExtractor {
    private DetailExtractor() {}

    private static final List<String> DESCRIPTION_SELECTORS = List.of(
            "[itemprop=description]", "[data-careersite-propertyid=description]", ".job-description", ".jobdescription",
            "#job-description", "[class*=job-description]", "[class*=jobDescription]", "[class*=job-detail]",
            "[class*=jobdetail]", "[class*=stellenbeschreibung]", "[class*=vacancy]", "article", "main", "#content", ".content");
    private static final Pattern APPLY_TEXT = Pattern.compile("(?i)jetzt bewerben|bewerben|online-bewerbung|apply now|apply|bewerbung starten|zur bewerbung");

    static RawJob enrich(RawJob job, Document doc) {
        List<RawJob> ld = JsonLdJobs.extract(doc, job.url());
        if (!ld.isEmpty() && ld.getFirst().descriptionHtml() != null) {
            RawJob l = ld.getFirst();
            return job.withDetails(l.descriptionHtml(), findApplyLink(doc), l.locations(), l.postedAt());
        }
        String html = null;
        for (String sel : DESCRIPTION_SELECTORS) {
            Element e = doc.selectFirst(sel);
            if (e != null && e.text().length() > 300) {
                e.select("script, style, nav, header, footer, form").remove();
                html = e.html();
                break;
            }
        }
        if (html == null && doc.body() != null) {
            Element body = doc.body().clone();
            body.select("script, style, nav, header, footer, form, noscript").remove();
            html = body.html();
        }
        List<String> locs = null;
        Element loc = doc.selectFirst("[itemprop=jobLocation], [data-careersite-propertyid=location], [class*=job-location], [class*=jobLocation], [class*=standort]");
        if (loc != null && loc.text().length() < 200) locs = List.of(loc.text());
        return job.withDetails(html, findApplyLink(doc), locs, null);
    }

    static String findApplyLink(Document doc) {
        for (Element a : doc.select("a[href]")) {
            if (APPLY_TEXT.matcher(a.text()).find() || a.hasClass("apply") || a.attr("href").contains("apply")) {
                String href = a.absUrl("href");
                if (!href.isBlank() && !href.startsWith("mailto:") && !href.startsWith("javascript:")) return href;
            }
        }
        return null;
    }

    static int textLength(Document doc) {
        return doc.body() == null ? 0 : doc.body().text().length();
    }
}

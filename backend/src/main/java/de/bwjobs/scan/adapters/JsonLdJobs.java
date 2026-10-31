package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.scan.RawJob;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** Reads schema.org JobPosting objects from JSON-LD script tags (Google for Jobs markup). */
public final class JsonLdJobs {
    private JsonLdJobs() {}

    public static List<RawJob> extract(Document doc, String pageUrl) {
        List<RawJob> out = new ArrayList<>();
        for (Element s : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode root = Sql.JSON.readTree(s.data().trim());
                collect(root, pageUrl, out);
            } catch (Exception ignored) {
                // malformed JSON-LD is common; skip it
            }
        }
        return out;
    }

    private static void collect(JsonNode n, String pageUrl, List<RawJob> out) {
        if (n == null) return;
        if (n.isArray()) {
            for (JsonNode c : n) collect(c, pageUrl, out);
            return;
        }
        if (!n.isObject()) return;
        if (n.has("@graph")) collect(n.get("@graph"), pageUrl, out);
        if (n.has("itemListElement")) {
            for (JsonNode item : n.get("itemListElement")) collect(item.has("item") ? item.get("item") : item, pageUrl, out);
        }
        if (isType(n, "JobPosting")) out.add(toJob(n, pageUrl));
    }

    private static boolean isType(JsonNode n, String type) {
        JsonNode t = n.get("@type");
        if (t == null) return false;
        if (t.isArray()) {
            for (JsonNode x : t) if (type.equals(x.asText())) return true;
            return false;
        }
        return type.equals(t.asText());
    }

    private static RawJob toJob(JsonNode n, String pageUrl) {
        String title = Json.text(n, "title");
        String url = Json.text(n, "url");
        if (url == null) url = pageUrl;
        String id = null;
        JsonNode ident = n.get("identifier");
        if (ident != null) id = ident.isObject() ? Json.text(ident, "value") : ident.asText();
        if (id == null) id = url;
        List<String> locs = new ArrayList<>();
        JsonNode jl = n.get("jobLocation");
        if (jl != null) {
            for (JsonNode l : jl.isArray() ? jl : List.of(jl)) {
                JsonNode a = l.path("address");
                if (a.isTextual()) {
                    locs.add(a.asText());
                } else {
                    String loc = String.join(", ", Json.nonBlank(Json.text(a, "postalCode"), Json.text(a, "addressLocality"),
                            Json.text(a, "addressRegion"), countryOf(a)));
                    if (!loc.isBlank()) locs.add(loc);
                }
            }
        }
        boolean remote = "TELECOMMUTE".equalsIgnoreCase(Json.text(n, "jobLocationType"));
        String desc = Json.text(n, "description");
        return new RawJob(id, title, url, null, locs, desc, Json.instant(Json.text(n, "datePosted")), remote,
                desc != null && desc.length() > 200);
    }

    private static String countryOf(JsonNode address) {
        JsonNode c = address.get("addressCountry");
        if (c == null) return null;
        return c.isObject() ? Json.text(c, "name") : c.asText();
    }
}

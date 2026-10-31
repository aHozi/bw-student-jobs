package de.bwjobs.job;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

import java.util.Set;

/** Converts HTML job descriptions to readable text (keeps line breaks) and safe HTML. */
public final class HtmlText {
    private HtmlText() {}

    private static final Set<String> BLOCK = Set.of("p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
            "tr", "section", "article", "header", "footer", "table", "dt", "dd");

    public static String toText(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, svg, iframe, form, nav").remove();
        StringBuilder sb = new StringBuilder();
        walk(doc.body() == null ? doc : doc.body(), sb);
        return sb.toString().replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static void walk(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                sb.append(t.text());
            } else if (child instanceof Element e) {
                String tag = e.tagName().toLowerCase();
                if (tag.equals("li")) sb.append("\n• ");
                else if (BLOCK.contains(tag)) sb.append('\n');
                walk(e, sb);
                if (BLOCK.contains(tag)) sb.append('\n');
            }
        }
    }

    /** Sanitized HTML safe to render in the frontend. Links become absolute (company site) and open in a new tab. */
    public static String safeHtml(String html, String baseUrl) {
        if (html == null) return null;
        Safelist safelist = Safelist.relaxed().removeTags("img")
                .addEnforcedAttribute("a", "target", "_blank")
                .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow");
        if (baseUrl == null || baseUrl.isBlank()) return Jsoup.clean(html, safelist);
        return Jsoup.clean(html, baseUrl, safelist.preserveRelativeLinks(false));
    }
}

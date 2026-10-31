package de.bwjobs.common;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private Text() {}

    /** Lowercase, strip accents (but keep umlaut info as ae/oe/ue), collapse whitespace. */
    public static String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        String noAccents = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.replaceAll("\\s+", " ").trim();
    }

    /** Normalizes a job title for fingerprinting: drop gender tags like (m/w/d), punctuation. */
    public static String normalizeTitle(String title) {
        String t = normalize(title)
                .replaceAll("\\((?:[mwfdx]\\s*/\\s*)+[mwfdx]\\)", " ")
                .replaceAll("\\b(?:[mwfdx]\\s*/\\s*){1,3}[mwfdx]\\b", " ")
                .replaceAll("\\(all genders?\\)|\\(gn\\)|\\*in\\b|:in\\b", " ")
                .replaceAll("[^a-z0-9+#]+", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

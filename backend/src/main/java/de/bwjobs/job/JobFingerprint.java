package de.bwjobs.job;

import de.bwjobs.common.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable identity of a posting across scans, reposts and sources: company + normalized title + city.
 * The city is the first location without postal code, region or country, so "70173 Stuttgart, Baden-Württemberg"
 * (Bundesagentur) and "Stuttgart, Baden-Württemberg, Germany" (LinkedIn) give the same fingerprint.
 */
public final class JobFingerprint {
    private JobFingerprint() {}

    public static String of(long companyId, String title, List<String> locations) {
        String key = companyId + "|" + Text.normalizeTitle(title) + "|" + city(locations);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String city(List<String> locations) {
        if (locations == null || locations.isEmpty() || locations.getFirst() == null) return "";
        String first = Text.normalize(locations.getFirst()).split("[,;(/|]")[0];
        return first.replaceAll("\\d", "").replaceAll("\\s+", " ").trim();
    }
}

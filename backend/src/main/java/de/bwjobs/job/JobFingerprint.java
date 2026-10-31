package de.bwjobs.job;

import de.bwjobs.common.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Stable identity of a posting across scans and reposts: company + normalized title + first location. */
public final class JobFingerprint {
    private JobFingerprint() {}

    public static String of(long companyId, String title, List<String> locations) {
        String loc = locations == null || locations.isEmpty() ? "" : Text.normalize(locations.getFirst());
        String key = companyId + "|" + Text.normalizeTitle(title) + "|" + loc;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

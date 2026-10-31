package de.bwjobs.job;

import de.bwjobs.common.Text;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether job locations are in Baden-Württemberg, using a city list and postal codes. */
public final class BwLocationMatcher {
    private static final Set<String> CITIES = loadCities();
    private static final Pattern PLZ = Pattern.compile("\\b(\\d{5})\\b");
    private static final Pattern REMOTE = Pattern.compile("remote|home ?office|mobiles arbeiten|hybrid|anywhere|deutschlandweit|germany \\(remote\\)|work from home");
    private static final Pattern OTHER_COUNTRY = Pattern.compile(
            "\\b(?:usa|united states|india|china|poland|polen|romania|rumaenien|hungary|ungarn|portugal|spain|spanien|france|frankreich|czech|tschechien|mexico|brazil|singapore|japan|korea|uk|united kingdom|austria|oesterreich|switzerland|schweiz|netherlands|niederlande|italy|italien|vietnam|malaysia|turkey|tuerkei|bulgaria|bulgarien|serbia|serbien|croatia|kroatien|slovakia|slowakei)\\b");

    private BwLocationMatcher() {}

    public record Result(Boolean inBw, boolean remote) {}

    public static Result match(Collection<String> locations, String descriptionHint) {
        boolean remote = false;
        Boolean inBw = null;
        boolean sawOtherKnown = false;
        for (String loc : locations) {
            String n = Text.normalize(loc);
            if (REMOTE.matcher(n).find()) remote = true;
            if (isBw(n)) {
                inBw = true;
            } else if (!n.isBlank() && (OTHER_COUNTRY.matcher(n).find() || looksLikeGermanNonBwPlz(n) || isKnownNonBwCity(n))) {
                sawOtherKnown = true;
            }
        }
        if (inBw == null && sawOtherKnown) inBw = false;
        if (inBw == null && descriptionHint != null) {
            String d = Text.normalize(Text.truncate(descriptionHint, 3000));
            if (d.contains("baden-wuerttemberg") || CITIES.stream().anyMatch(c -> containsWord(d, c) && c.length() > 5)) inBw = true;
        }
        if (!remote && descriptionHint != null) {
            String d = Text.normalize(Text.truncate(descriptionHint, 4000));
            remote = d.contains("100% remote") || d.contains("vollstaendig remote") || d.contains("fully remote");
        }
        return new Result(inBw, remote);
    }

    public static boolean isBw(String location) {
        // Neu-Ulm and Walldorf (Hessen) are outside BW but contain BW city names.
        String normalizedLocation = location.replace("neu-ulm", " ").replace("walldorf (hessen)", " ")
                .replace("moerfelden-walldorf", " ");
        if (normalizedLocation.contains("baden-wuerttemberg") || normalizedLocation.contains("baden wuerttemberg")) return true;
        Matcher m = PLZ.matcher(normalizedLocation);
        while (m.find()) {
            int plz = Integer.parseInt(m.group(1));
            if (plz >= 70000 && plz <= 79999) return true;
            if ((plz >= 68000 && plz <= 69999) || (plz >= 88000 && plz <= 89999) || (plz >= 97800 && plz <= 97999)) {
                // mixed areas: fall through to city check
                break;
            }
        }
        for (String city : CITIES) {
            if (containsWord(normalizedLocation, city)) return true;
        }
        return false;
    }

    private static final Set<String> NON_BW = Set.of("muenchen", "munich", "berlin", "hamburg", "frankfurt", "koeln", "cologne",
            "duesseldorf", "dresden", "leipzig", "hannover", "nuernberg", "nuremberg", "bremen", "essen", "dortmund", "wolfsburg",
            "ingolstadt", "regensburg", "erlangen", "augsburg", "mainz", "wiesbaden", "darmstadt", "kaiserslautern", "ludwigshafen",
            "saarbruecken", "bonn", "aachen", "jena", "kassel", "braunschweig", "kiel", "rostock", "magdeburg", "erfurt", "potsdam",
            "neu-ulm", "lindau", "walldorf (hessen)");

    private static boolean isKnownNonBwCity(String n) {
        return NON_BW.stream().anyMatch(c -> containsWord(n, c));
    }

    private static boolean looksLikeGermanNonBwPlz(String n) {
        Matcher m = PLZ.matcher(n);
        if (m.find()) {
            int plz = Integer.parseInt(m.group(1));
            return !(plz >= 68000 && plz <= 79999) && !(plz >= 88000 && plz <= 89999) && !(plz >= 97800 && plz <= 97999);
        }
        return false;
    }

    private static boolean containsWord(String haystack, String word) {
        int idx = haystack.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetter(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean endOk = end == haystack.length() || !Character.isLetter(haystack.charAt(end));
            if (startOk && endOk) return true;
            idx = haystack.indexOf(word, idx + 1);
        }
        return false;
    }

    public static Set<String> cities() {
        return CITIES;
    }

    private static Set<String> loadCities() {
        Set<String> out = new LinkedHashSet<>();
        try (var in = new ClassPathResource("taxonomy/bw-cities.txt").getInputStream();
             var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(Text.normalize(line));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load bw-cities.txt", e);
        }
        return Collections.unmodifiableSet(out);
    }
}

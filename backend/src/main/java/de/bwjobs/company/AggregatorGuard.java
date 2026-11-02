package de.bwjobs.company;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rejects URLs on job aggregators (LinkedIn, StepStone, Indeed, ...).
 * Applied to seed career URLs, discovered career pages and every scanned job link.
 */
@Component
public class AggregatorGuard {
    private final Set<String> domains;

    public AggregatorGuard() {
        this.domains = load();
    }

    public boolean isAggregator(String url) {
        String host = host(url);
        if (host == null) return false;
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /** Display name of the job board a URL belongs to, or null if it is not a job board. */
    public String platformName(String url) {
        if (!isAggregator(url)) return null;
        String h = host(url);
        if (h.contains("linkedin")) return "LinkedIn";
        if (h.contains("stepstone")) return "StepStone";
        if (h.contains("indeed")) return "Indeed";
        if (h.contains("arbeitsagentur")) return "Bundesagentur für Arbeit";
        if (h.contains("adzuna")) return "Adzuna";
        if (h.contains("xing")) return "XING";
        return "the job board";
    }

    public void requireOfficial(String url) {
        if (isAggregator(url)) {
            throw new IllegalArgumentException("Job aggregators are not allowed as sources: " + url);
        }
    }

    public Set<String> domains() {
        return domains;
    }

    static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String u = url.contains("://") ? url : "https://" + url;
            String h = URI.create(u.trim().replace(" ", "%20")).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> load() {
        Set<String> out = new TreeSet<>();
        try (var in = new ClassPathResource("taxonomy/aggregator-domains.txt").getInputStream();
             var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load aggregator-domains.txt", e);
        }
        return out;
    }
}

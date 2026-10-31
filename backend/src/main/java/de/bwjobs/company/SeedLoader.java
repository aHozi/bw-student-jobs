package de.bwjobs.company;

import de.bwjobs.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the curated list of Baden-Württemberg companies on startup. Existing companies are
 * left untouched, so admins can edit them. New entries in the YAML are added on the next start.
 */
@Component
public class SeedLoader {
    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final CompanyRepository companies;
    private final AggregatorGuard guard;
    private final AppProperties props;

    public SeedLoader(CompanyRepository companies, AggregatorGuard guard, AppProperties props) {
        this.companies = companies;
        this.guard = guard;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.seed() == null || !props.seed().enabled()) return;
        int added = load("seed/companies-bw.yaml");
        log.info("Company seed: {} new companies added ({} total)", added, companies.count());
    }

    @SuppressWarnings("unchecked")
    public int load(String resource) {
        List<Map<String, Object>> entries;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            entries = (List<Map<String, Object>>) root.get("companies");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
        int added = 0;
        for (Map<String, Object> e : entries) {
            String name = str(e.get("name"));
            if (name == null || companies.findByName(name).isPresent()) continue;
            String website = str(e.get("website"));
            String careerUrl = str(e.get("careerUrl"));
            if ((careerUrl != null && guard.isAggregator(careerUrl)) || (website != null && guard.isAggregator(website))) {
                log.warn("Seed entry {} points to an aggregator - skipped", name);
                continue;
            }
            AtsType ats = AtsType.UNKNOWN;
            String tenant = null;
            String hint = str(e.get("ats"));
            if (hint != null && hint.contains(":")) {
                ats = AtsType.valueOf(hint.substring(0, hint.indexOf(':')).trim().toUpperCase());
                tenant = hint.substring(hint.indexOf(':') + 1).trim();
            } else {
                Optional<AtsDetector.Detection> d = AtsDetector.fromUrl(careerUrl);
                if (d.isPresent()) {
                    ats = d.get().type();
                    tenant = d.get().tenant();
                }
            }
            companies.insert(name, website, careerUrl, str(e.get("city")), str(e.get("industry")), str(e.get("size")),
                    ats, tenant, "ACTIVE", "SEED", props.scan().defaultIntervalHours());
            added++;
        }
        return added;
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString().trim();
    }
}

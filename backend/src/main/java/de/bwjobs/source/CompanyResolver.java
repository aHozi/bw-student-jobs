package de.bwjobs.source;

import de.bwjobs.common.Text;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps employer names from job sources to company rows. "Robert Bosch GmbH", "Robert Bosch" and
 * "ROBERT BOSCH GMBH" all resolve to the same company; unknown employers are created automatically.
 */
@Component
public class CompanyResolver {
    static final String ANONYMOUS = "Arbeitgeber nicht genannt";

    private final CompanyRepository companies;

    public CompanyResolver(CompanyRepository companies) {
        this.companies = companies;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillKeys() {
        for (Map.Entry<Long, String> e : companies.withoutNameKey()) companies.setNameKey(e.getKey(), key(e.getValue()));
    }

    public synchronized Company resolve(String employerName, String city) {
        String name = employerName == null || employerName.isBlank() ? ANONYMOUS : employerName.trim();
        String key = key(name);
        if (key.isEmpty()) key = key(ANONYMOUS);
        var existing = companies.findByNameKey(key);
        if (existing.isPresent()) return existing.get();
        try {
            return companies.insertAuto(Text.truncate(name, 200), key, city);
        } catch (DuplicateKeyException e) {
            // same display name with a different key, or a concurrent insert
            return companies.findByNameKey(key).or(() -> companies.findByName(name)).orElseThrow(() -> e);
        }
    }

    /** Normalized company name: lowercase, no legal form, no punctuation. */
    public static String key(String name) {
        String n = Text.normalize(name)
                .replaceAll("\\(.*?\\)", " ")
                .replace("&", " ")
                .replaceAll("\\b(?:gmbh|ag|se|kg|kgaa|mbh|ug|ohg|gbr|e\\.?\\s?v|co|inc|ltd|llc|plc|corp|corporation|holding|haftungsbeschraenkt)\\b\\.?", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return n;
    }
}

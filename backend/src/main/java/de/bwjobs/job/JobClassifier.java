package de.bwjobs.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The classification steps every source shares: student type → IT field → language → BW location → fingerprint.
 * Returns null when a job is not a student IT job.
 */
public final class JobClassifier {
    private JobClassifier() {}

    public record Classified(Set<StudentType> types, ItFieldTaxonomy.Result it, LanguageRequirementExtractor.Result lang,
                             BwLocationMatcher.Result loc, String text, String fingerprint) {
        public List<String> typeNames() {
            return types.stream().map(Enum::name).sorted().toList();
        }

        public List<String> fieldNames() {
            return new ArrayList<>(it.fields());
        }

        public List<String> techTermList() {
            return new ArrayList<>(it.techTerms());
        }
    }

    public static Classified classify(long companyId, String title, String descriptionHtml, List<String> locations) {
        if (title == null || title.isBlank()) return null;
        Set<StudentType> types = StudentTypeRules.detect(title);
        if (types.isEmpty()) return null;
        String text = HtmlText.toText(descriptionHtml);
        ItFieldTaxonomy.Result it = ItFieldTaxonomy.classify(title, text);
        if (!it.isIt()) return null;
        LanguageRequirementExtractor.Result lang = LanguageRequirementExtractor.extract(title, text);
        BwLocationMatcher.Result loc = BwLocationMatcher.match(locations == null ? List.of() : locations, text);
        return new Classified(types, it, lang, loc, text, JobFingerprint.of(companyId, title, locations));
    }
}

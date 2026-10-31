package de.bwjobs.job;

import de.bwjobs.common.Text;
import de.bwjobs.profile.UserPreferences;
import de.bwjobs.profile.UserProfile;

import java.util.*;

/**
 * Fast, free match score (0-100) computed for every job on every search.
 * Skills 55 · IT field 15 · student type 10 · language fit 20.
 * The optional AI score (Claude) is shown next to it when the user requests one.
 */
public final class KeywordMatcher {
    private KeywordMatcher() {}

    public record Score(int total, List<String> matchedSkills, List<String> missingSkills, String languageFit) {}

    public static Score score(Set<String> userTerms, UserProfile profile, List<String> jobTerms, List<String> jobFields,
                              List<String> jobTypes, String germanLevel, String englishLevel, String postingLanguage) {
        UserPreferences prefs = profile.preferences().withDefaults();
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String t : jobTerms) {
            if (userTerms.contains(t)) matched.add(t);
            else missing.add(t);
        }
        double skills = jobTerms.isEmpty() ? 0.5 : (double) matched.size() / jobTerms.size();
        // Saturate: matching 70% of the listed tech already counts as a full skill score.
        double skillPoints = Math.min(1.0, skills / 0.7) * 55;

        double fieldPoints = prefs.itFields().isEmpty() ? 10
                : jobFields.stream().anyMatch(prefs.itFields()::contains) ? 15 : 0;
        double typePoints = jobTypes.stream().anyMatch(prefs.studentTypes()::contains) ? 10 : 3;

        int g = profile.germanRank();
        int e = profile.englishRank();
        double germanPoints = languagePoints(GermanLevel.valueOf(germanLevel).requiredRank, g, "DE".equals(postingLanguage), 15);
        double englishPoints = languagePoints(EnglishLevel.valueOf(englishLevel).requiredRank, e, "EN".equals(postingLanguage), 5);
        String fit = germanPoints >= 15 && englishPoints >= 5 ? "GOOD"
                : germanPoints + englishPoints >= 10 ? "PARTIAL" : "WEAK";
        int total = (int) Math.round(skillPoints + fieldPoints + typePoints + germanPoints + englishPoints);
        return new Score(Math.max(0, Math.min(100, total)), matched, missing, fit);
    }

    /** required < 0 means unknown: assume the posting language is needed at a good level. */
    private static double languagePoints(int required, int userRank, boolean postingInThisLanguage, double max) {
        if (required < 0) required = postingInThisLanguage ? 4 : 0;
        if (required <= 1) return max;
        if (userRank >= required) return max;
        int gap = required - userRank;
        return Math.max(0, max - gap * (max / 3));
    }

    /** All technology terms the user has: profile skills + terms found in their CV text. */
    public static Set<String> userTerms(UserProfile p, String cvText) {
        Set<String> out = new HashSet<>();
        if (p.skills() != null) {
            for (String s : p.skills()) {
                String n = Text.normalize(s);
                out.add(n);
                out.addAll(ItFieldTaxonomy.techTerms(n));
            }
        }
        if (cvText != null) out.addAll(ItFieldTaxonomy.techTerms(cvText));
        return out;
    }
}

package de.bwjobs.profile;

import java.util.List;

/**
 * User-controlled filters and defaults. Nothing here is hardcoded per person:
 * every user picks their own accepted language levels, fields, locations.
 */
public record UserPreferences(
        List<String> studentTypes,          // WERKSTUDENT, PRAKTIKUM, ABSCHLUSSARBEIT, DUALES_STUDIUM
        List<String> itFields,              // empty = all
        List<String> acceptedGermanLevels,  // empty = all (never auto-excluded)
        List<String> acceptedEnglishLevels, // empty = all
        Boolean includeUnknownLanguage,
        String locationMode,                // BW_ONLY / BW_OR_REMOTE / ALL
        List<String> cities,
        String defaultDocumentMode,         // ORIGINAL / TAILORED
        String coverLetterLanguage,         // AUTO / DE / EN
        Integer minScore) {

    public static UserPreferences defaults() {
        return new UserPreferences(
                List.of("WERKSTUDENT", "PRAKTIKUM", "ABSCHLUSSARBEIT"),
                List.of(), List.of(), List.of(), true, "BW_OR_REMOTE", List.of(), "ORIGINAL", "AUTO", 0);
    }

    /** Fills nulls with defaults so callers never deal with missing values. */
    public UserPreferences withDefaults() {
        UserPreferences d = defaults();
        return new UserPreferences(
                studentTypes != null ? studentTypes : d.studentTypes,
                itFields != null ? itFields : d.itFields,
                acceptedGermanLevels != null ? acceptedGermanLevels : d.acceptedGermanLevels,
                acceptedEnglishLevels != null ? acceptedEnglishLevels : d.acceptedEnglishLevels,
                includeUnknownLanguage != null ? includeUnknownLanguage : d.includeUnknownLanguage,
                locationMode != null ? locationMode : d.locationMode,
                cities != null ? cities : d.cities,
                defaultDocumentMode != null ? defaultDocumentMode : d.defaultDocumentMode,
                coverLetterLanguage != null ? coverLetterLanguage : d.coverLetterLanguage,
                minScore != null ? minScore : d.minScore);
    }
}

package de.bwjobs.profile;

import java.util.List;

public record UserProfile(
        long userId,
        String fullName,
        String email,
        String phone,
        String street,
        String postalCode,
        String city,
        String linkedinUrl,
        String githubUrl,
        String websiteUrl,
        String university,
        String studyProgram,
        String degree,
        Integer semester,
        String availableFrom,
        Integer hoursPerWeek,
        List<String> skills,
        List<LanguageSkill> languages,
        String summary,
        UserPreferences preferences,
        boolean aiConsent) {

    public String firstName() {
        if (fullName == null || fullName.isBlank()) return "";
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1));
    }

    public String lastName() {
        if (fullName == null || fullName.isBlank()) return "";
        String[] parts = fullName.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    /** German level of the user as CEFR-like rank: 0 none .. 6 C2, 7 native. */
    public int germanRank() {
        if (languages == null) return 0;
        return languages.stream()
                .filter(l -> l.language() != null && l.language().toLowerCase().matches("deutsch|german"))
                .mapToInt(l -> LanguageRanks.rank(l.level()))
                .max().orElse(0);
    }

    public int englishRank() {
        if (languages == null) return 0;
        return languages.stream()
                .filter(l -> l.language() != null && l.language().toLowerCase().matches("englisch|english"))
                .mapToInt(l -> LanguageRanks.rank(l.level()))
                .max().orElse(0);
    }
}

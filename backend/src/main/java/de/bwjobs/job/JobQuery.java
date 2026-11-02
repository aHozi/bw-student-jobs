package de.bwjobs.job;

import java.util.List;

public record JobQuery(
        String q,
        List<String> sources,      // EMAIL_ALERT / ARBEITSAGENTUR / ADZUNA / CAREER_PAGE (empty = all)
        List<String> studentTypes,
        List<String> itFields,
        List<String> germanLevels,
        List<String> englishLevels,
        Boolean includeUnknownLanguage,
        String locationMode,     // BW_ONLY / BW_OR_REMOTE / ALL
        String city,
        Integer postedWithinDays,
        Integer minScore,
        Long companyId,
        Boolean hideApplied,
        String sort,             // match / date
        int page,
        int size) {}

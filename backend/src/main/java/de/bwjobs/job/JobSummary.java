package de.bwjobs.job;

import java.time.Instant;
import java.util.List;

public record JobSummary(
        long id, String title, long companyId, String companyName, String companyCity, List<String> locations,
        Boolean inBw, boolean remote, List<String> studentTypes, List<String> itFields, String germanLevel,
        String englishLevel, String postingLanguage, Instant postedAt, Instant firstSeenAt, String url,
        int keywordScore, Integer aiScore, int score, String languageFit, String applicationStatus, Long applicationId,
        List<String> sources, List<String> platforms, boolean applyIsBoard, boolean snippetOnly) {}

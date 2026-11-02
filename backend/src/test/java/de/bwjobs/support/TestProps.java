package de.bwjobs.support;

import de.bwjobs.common.AppProperties;

import java.util.List;

public final class TestProps {
    private TestProps() {}

    public static AppProperties props() {
        return new AppProperties("test-secret-test-secret-test-secret-123", 1, List.of(), List.of(),
                new AppProperties.Storage("target/test-files", null), new AppProperties.Seed(false),
                new AppProperties.Scan(false, 1, 12, 6, 48, "BWStudentJobsBot/test", 0, 10, 20, false),
                new AppProperties.Browser(null, true),
                new AppProperties.Ai("auto", null, null, "claude-opus-5-5", 10, false,
                        new AppProperties.Gemini(null, "gemini-3.8-flash", "http://127.0.0.1:1")),
                new AppProperties.Sources(
                        new AppProperties.Arbeitsagentur(true, "http://127.0.0.1:1", "jobboerse-jobsuche",
                                List.of("Werkstudent Informatik"), List.of("Stuttgart"), 30, 60, 50),
                        new AppProperties.Adzuna("id", "key", "http://127.0.0.1:1", List.of("werkstudent informatik"),
                                "Baden-Württemberg", 30, 1),
                        new AppProperties.Email(true, 30, 30)));
    }
}

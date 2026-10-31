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
                new AppProperties.Ai(null, null, "claude-opus-5-5", 10, false));
    }
}

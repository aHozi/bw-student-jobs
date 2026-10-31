package de.bwjobs.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.sql.DriverManager;

/** Skips integration tests when the test database is not running (start it with: docker compose up -d db). */
public class DatabaseAvailable implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String url = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/bwjobs_test");
        try (var c = DriverManager.getConnection(url, System.getenv().getOrDefault("TEST_DB_USER", "bwjobs"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "bwjobs"))) {
            return ConditionEvaluationResult.enabled("database available");
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled("Test database not reachable: " + e.getMessage());
        }
    }
}

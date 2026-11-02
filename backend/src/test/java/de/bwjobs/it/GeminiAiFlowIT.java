package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.job.JobRepository;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The AI features with Gemini as provider, against a mock of the Gemini generateContent endpoint. */
class GeminiAiFlowIT extends IntegrationTestBase {
    static final MockSite GEMINI;

    static {
        try {
            GEMINI = new MockSite();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GEMINI.dynamic("/v1beta/models/gemini-test:generateContent", req -> {
            String body = new String(req.body(), StandardCharsets.UTF_8);
            Object out = body.contains("tailor a student's CV")
                    ? Map.of("language", "DE", "headline", "Informatikstudent (Gemini)", "summary", "Java und Spring Boot.",
                    "skills", List.of("Java"), "sections", List.of(), "changes", List.of("Zusammenfassung gekürzt"))
                    : Map.of("score", 64, "summary", "Gemini says partial fit.", "strengths", List.of("Java"), "gaps", List.of("Kafka"),
                    "languageAssessment", "B2 is enough.", "recommendation", "Apply.");
            return new MockSite.Response(200, "application/json", Sql.toJson(Map.of("candidates", List.of(Map.of(
                    "content", Map.of("role", "model", "parts", List.of(Map.of("text", Sql.toJson(out)))), "finishReason", "STOP")))));
        });
    }

    @DynamicPropertySource
    static void gemini(DynamicPropertyRegistry r) {
        r.add("app.ai.provider", () -> "auto");
        r.add("app.ai.api-key", () -> "");          // no Claude key → auto picks Gemini
        r.add("app.ai.gemini.api-key", () -> "g-key");
        r.add("app.ai.gemini.model", () -> "gemini-test");
        r.add("app.ai.gemini.base-url", () -> GEMINI.base() + "/v1beta");
    }

    @AfterAll
    static void stop() {
        GEMINI.close();
    }

    @Autowired CompanyRepository companies;
    @Autowired JobRepository jobs;

    @Test
    void geminiPowersScoreAndTailoring() throws Exception {
        Company c = companies.insert("Gemini Test GmbH", null, null, "Ulm", "IT", "M", AtsType.UNKNOWN, null, "ACTIVE", "ADMIN", 12);
        long jobId = jobs.upsert(new JobRepository.Upsert(c.id(), "1", "https://example.org/jobs/1", null, "Werkstudent Java (m/w/d)",
                "<p>Java</p>", "Java und Spring Boot, gute Deutschkenntnisse.", List.of("Ulm"), false, true, List.of("WERKSTUDENT"),
                List.of("JAVA"), List.of("java"), "B2", "GOOD", "DE", null, Instant.now(), "fp-gemini")).id();
        String token = register("gemini@example.org");
        setProfile(token, "{\"fullName\":\"Max Mustermann\",\"aiConsent\":true}");
        long cvId = uploadCv(token, CV_TEXT);

        JsonNode status = getJson(token, "/api/ai/status");
        assertThat(status.get("provider").asText()).isEqualTo("gemini");
        assertThat(status.get("model").asText()).isEqualTo("gemini-test");

        assertThat(postJson(token, "/api/jobs/" + jobId + "/ai-score", "{}").getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson(token, "/api/jobs/" + jobId).get("aiScore").get("score").asInt()).isEqualTo(64);

        var cv = postJson(token, "/api/jobs/" + jobId + "/tailor-cv", "{\"cvDocumentId\":" + cvId + "}");
        assertThat(cv.getResponse().getStatus()).as(cv.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(cv.getResponse().getContentAsString()).contains("Informatikstudent (Gemini)");
        assertThat(getJson(token, "/api/ai/status").get("usedToday").asInt()).isEqualTo(2);
        assertThat(GEMINI.requests).hasSize(2);
        assertThat(GEMINI.requests.getFirst().headers().get("x-goog-api-key")).isEqualTo("g-key");
    }
}

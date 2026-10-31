package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.document.TextExtractor;
import de.bwjobs.job.JobRepository;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** AI features end to end against a local mock of the Claude Messages API. */
class AiFlowIT extends IntegrationTestBase {
    static final MockSite CLAUDE;

    static {
        try {
            CLAUDE = new MockSite();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        CLAUDE.dynamic("/v1/messages", req -> {
            String body = new String(req.body(), java.nio.charset.StandardCharsets.UTF_8);
            Object out;
            if (body.contains("tailor a student's CV")) {
                out = Map.of("language", "DE", "headline", "Informatikstudent mit Fokus Backend",
                        "summary", "Student der Informatik mit Erfahrung in Java und Spring Boot.",
                        "skills", List.of("Java", "Spring Boot", "Docker"),
                        "sections", List.of(Map.of("title", "Projekte", "entries", List.of(Map.of("heading", "REST-API", "subheading", "Uni-Projekt",
                                "period", "2025", "bullets", List.of("REST-API mit Spring Boot und PostgreSQL entwickelt"))))),
                        "changes", List.of("Projekte nach oben verschoben"));
            } else if (body.contains("cover letter")) {
                out = Map.of("language", "DE", "subject", "Bewerbung als Werkstudent Java (m/w/d)", "salutation", "Sehr geehrte Damen und Herren,",
                        "paragraphs", List.of("Ihr Team entwickelt Backend-Services – genau das möchte ich lernen.", "In meinem Studium habe ich eine REST-API mit Spring Boot gebaut."),
                        "closing", "Mit freundlichen Grüßen");
            } else {
                out = Map.of("score", 82, "summary", "Strong backend fit.", "strengths", List.of("Java", "Spring Boot"),
                        "gaps", List.of("Kubernetes"), "languageAssessment", "Native German meets the C1 requirement.",
                        "recommendation", "Apply with tailored CV.");
            }
            String msg = Sql.toJson(Map.of("id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                    "content", List.of(Map.of("type", "text", "text", Sql.toJson(out))), "stop_reason", "end_turn",
                    "usage", Map.of("input_tokens", 100, "output_tokens", 50)));
            return new MockSite.Response(200, "application/json", msg);
        });
    }

    @DynamicPropertySource
    static void claude(DynamicPropertyRegistry r) {
        r.add("app.ai.base-url", CLAUDE::base);
    }

    @AfterAll
    static void stop() {
        CLAUDE.close();
    }

    @Autowired CompanyRepository companies;
    @Autowired JobRepository jobs;
    @Autowired TextExtractor extractor;

    @Test
    void aiScoreTailoredCvAndCoverLetter() throws Exception {
        Company c = companies.insert("AI Test GmbH", "https://ai.example.de", null, "Karlsruhe", "Software", "M", AtsType.GENERIC, null, "ACTIVE", "ADMIN", 12);
        long jobId = jobs.upsert(new JobRepository.Upsert(c.id(), "1", "https://ai.example.de/jobs/1", null, "Werkstudent Java (m/w/d)",
                "<p>Java</p>", "Wir suchen Unterstützung im Backend mit Java und Spring Boot. Sehr gute Deutschkenntnisse.",
                List.of("Karlsruhe"), false, true, List.of("WERKSTUDENT"), List.of("JAVA"), List.of("java", "spring boot", "kubernetes"),
                "C1_FLUENT", "GOOD", "DE", null, Instant.now(), "fp-ai")).id();
        String token = register("ai@example.org");
        setProfile(token, "{\"fullName\":\"Max Mustermann\",\"city\":\"Karlsruhe\",\"email\":\"max@example.org\"}");
        long cvId = uploadCv(token, CV_TEXT);

        // Without consent, nothing is sent to the AI
        assertThat(postJson(token, "/api/jobs/" + jobId + "/ai-score", "{}").getResponse().getStatus()).isEqualTo(403);
        assertThat(CLAUDE.requests).isEmpty();
        setProfile(token, "{\"aiConsent\":true}");

        // AI score is stored and shown with the job
        MvcResult score = postJson(token, "/api/jobs/" + jobId + "/ai-score", "{}");
        assertThat(score.getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson(token, "/api/jobs/" + jobId).get("aiScore").get("score").asInt()).isEqualTo(82);
        assertThat(getJson(token, "/api/jobs").get("items").get(0).get("score").asInt()).isEqualTo(82);

        var req = CLAUDE.requests.getFirst();
        String sent = new String(req.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sent).contains("\"model\":\"claude-opus-5-5\"").contains("\"fallbacks\":\"default\"").contains("output_config")
                .contains("<job_posting>").contains("<cv>").contains("Max Mustermann");
        assertThat(req.headers().get("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        assertThat(req.headers().get("authorization")).isEqualTo("Bearer test-key"); // SDK 3.x sends the key as a bearer credential

        // Tailored CV → new document, original untouched
        MvcResult cv = postJson(token, "/api/jobs/" + jobId + "/tailor-cv", "{\"cvDocumentId\":" + cvId + "}");
        assertThat(cv.getResponse().getStatus()).as(cv.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode cvDoc = Sql.JSON.readTree(cv.getResponse().getContentAsString());
        assertThat(cvDoc.get("original").asBoolean()).isFalse();
        assertThat(cvDoc.get("derivedFromId").asLong()).isEqualTo(cvId);
        byte[] pdf = download(token, cvDoc.get("id").asLong());
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        assertThat(extractor.extract(pdf)).contains("Max Mustermann").contains("Informatikstudent mit Fokus Backend").contains("KENNTNISSE");
        assertThat(new String(download(token, cvId))).isEqualTo(CV_TEXT);

        // User edits the generated CV → PDF re-rendered
        JsonNode content = Sql.JSON.readTree(cvDoc.get("contentJson").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) content).put("headline", "Java-Entwickler in Ausbildung");
        assertThat(putJson(token, "/api/documents/" + cvDoc.get("id").asLong() + "/content", content.toString()).getResponse().getStatus()).isEqualTo(200);
        assertThat(extractor.extract(download(token, cvDoc.get("id").asLong()))).contains("Java-Entwickler in Ausbildung");

        // Cover letter with German umlauts and address block
        MvcResult cl = postJson(token, "/api/jobs/" + jobId + "/cover-letter", "{\"cvDocumentId\":" + cvId + ",\"notes\":\"Ich kann 20 h/Woche arbeiten\"}");
        assertThat(cl.getResponse().getStatus()).isEqualTo(200);
        JsonNode clDoc = Sql.JSON.readTree(cl.getResponse().getContentAsString());
        assertThat(clDoc.get("kind").asText()).isEqualTo("COVER_LETTER");
        String letterText = extractor.extract(download(token, clDoc.get("id").asLong()));
        assertThat(letterText).contains("Bewerbung als Werkstudent Java").contains("Mit freundlichen Grüßen").contains("AI Test GmbH").contains("Karlsruhe,");
        assertThat(new String(CLAUDE.requests.getLast().body(), java.nio.charset.StandardCharsets.UTF_8)).contains("<notes>");

        // Quota is counted per user
        assertThat(getJson(token, "/api/ai/status").get("usedToday").asInt()).isEqualTo(3);
    }

    private byte[] download(String token, long docId) throws Exception {
        return mvc.perform(get("/api/documents/" + docId + "/download").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsByteArray();
    }
}

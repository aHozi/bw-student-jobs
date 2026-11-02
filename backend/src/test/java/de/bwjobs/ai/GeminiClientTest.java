package de.bwjobs.ai;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Sql;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiClientTest {
    MockSite site;
    GeminiClient client;
    volatile String answer;
    volatile int status = 200;

    @BeforeEach
    void setUp() throws Exception {
        site = new MockSite();
        site.dynamic("/v1beta/models/gemini-test:generateContent", r -> new MockSite.Response(status, "application/json", answer));
        var base = de.bwjobs.support.TestProps.props();
        var props = new AppProperties(base.jwtSecret(), base.jwtTtlHours(), base.adminEmails(), base.corsOrigins(), base.storage(),
                base.seed(), base.scan(), base.browser(),
                new AppProperties.Ai("gemini", null, null, "unused", 10, false,
                        new AppProperties.Gemini("g-key", "gemini-test", site.base() + "/v1beta")),
                base.sources());
        client = new GeminiClient(props);
    }

    @AfterEach
    void tearDown() {
        site.close();
    }

    static String candidate(String text, String finish) {
        return Sql.toJson(Map.of("candidates", List.of(Map.of("content", Map.of("role", "model",
                "parts", List.of(Map.of("text", "thinking...", "thought", true), Map.of("text", text))), "finishReason", finish))));
    }

    @Test
    void sendsSchemaAndParsesJson() throws Exception {
        answer = candidate(Sql.toJson(Map.of("score", 77, "summary", "Good fit", "strengths", List.of("Java"), "gaps", List.of(),
                "languageAssessment", "ok", "recommendation", "apply")), "STOP");
        AiModels.MatchAssessment a = client.structured(1L, "system prompt", "user text", AiModels.MatchAssessment.class, 1000);
        assertThat(a.score()).isEqualTo(77);
        assertThat(a.strengths()).containsExactly("Java");

        var req = site.requests.getFirst();
        assertThat(req.headers().get("x-goog-api-key")).isEqualTo("g-key");
        JsonNode body = Sql.JSON.readTree(new String(req.body(), StandardCharsets.UTF_8));
        assertThat(body.at("/systemInstruction/parts/0/text").asText()).isEqualTo("system prompt");
        assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo("user text");
        assertThat(body.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/responseSchema/type").asText()).isEqualTo("OBJECT");
        assertThat(body.at("/generationConfig/responseSchema/properties/score/type").asText()).isEqualTo("INTEGER");
        assertThat(body.at("/generationConfig/responseSchema/properties/strengths/items/type").asText()).isEqualTo("STRING");
        assertThat(body.at("/generationConfig/responseSchema/properties/score/description").asText()).contains("0-100");
        assertThat(body.at("/generationConfig/maxOutputTokens").asLong()).isEqualTo(1000 + GeminiClient.THINKING_HEADROOM);
    }

    @Test
    void nestedRecordsBecomeNestedSchemas() {
        Map<String, Object> s = GeminiSchema.of(AiModels.TailoredCv.class);
        JsonNode n = Sql.JSON.valueToTree(s);
        assertThat(n.at("/properties/sections/items/properties/entries/items/properties/bullets/type").asText()).isEqualTo("ARRAY");
        assertThat(n.at("/required").size()).isEqualTo(6);
    }

    @Test
    void safetyBlocksAndLimitsBecomeClearErrors() {
        answer = candidate("{}", "SAFETY");
        assertThatThrownBy(() -> client.structured(1L, "s", "u", AiModels.MatchAssessment.class, 100))
                .isInstanceOf(ApiException.class).hasMessageContaining("declined");
        answer = Sql.toJson(Map.of("promptFeedback", Map.of("blockReason", "PROHIBITED_CONTENT")));
        assertThatThrownBy(() -> client.structured(1L, "s", "u", AiModels.MatchAssessment.class, 100))
                .hasMessageContaining("PROHIBITED_CONTENT");
        answer = candidate("{\"score\":", "MAX_TOKENS");
        assertThatThrownBy(() -> client.structured(1L, "s", "u", AiModels.MatchAssessment.class, 100)).hasMessageContaining("cut off");
        status = 429;
        answer = "{}";
        assertThatThrownBy(() -> client.structured(1L, "s", "u", AiModels.MatchAssessment.class, 100)).hasMessageContaining("busy");
    }
}

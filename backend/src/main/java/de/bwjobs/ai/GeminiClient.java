package de.bwjobs.ai;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import de.bwjobs.common.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Google Gemini API over REST: POST {base}/models/{model}:generateContent with JSON output constrained by a
 * responseSchema built from the same records as the Claude calls. The key is sent in the x-goog-api-key header.
 */
@Component
public class GeminiClient implements AiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    /** Thinking models count reasoning tokens against maxOutputTokens, so leave room for them. */
    static final int THINKING_HEADROOM = 8192;
    private static final Set<String> BLOCKED = Set.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE");

    private final AppProperties.Gemini cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public GeminiClient(AppProperties props) {
        this.cfg = props.ai().gemini();
    }

    @Override
    public boolean enabled() {
        return cfg != null && cfg.enabled();
    }

    @Override
    public String provider() {
        return "gemini";
    }

    @Override
    public String model() {
        return cfg == null ? null : cfg.model();
    }

    @Override
    public <T> T structured(Long userId, String system, String userMessage, Class<T> type, long maxTokens) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", GeminiSchema.of(type),
                        "maxOutputTokens", maxTokens + THINKING_HEADROOM));
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/models/" + cfg.model() + ":generateContent"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", cfg.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(Sql.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 429) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service is busy. Please try again in a minute.");
        if (resp.statusCode() >= 300) {
            log.warn("Gemini API error {}: {}", resp.statusCode(), de.bwjobs.common.Text.truncate(resp.body(), 500));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI service error (" + resp.statusCode() + ").");
        }
        try {
            JsonNode root = Sql.JSON.readTree(resp.body());
            String block = root.path("promptFeedback").path("blockReason").asText("");
            if (!block.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The AI declined this request (" + block + ").");
            JsonNode cand = root.path("candidates").path(0);
            String finish = cand.path("finishReason").asText("");
            if (BLOCKED.contains(finish)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The AI declined this request (" + finish + ").");
            if ("MAX_TOKENS".equals(finish)) throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI response was cut off. Please try again.");
            StringBuilder text = new StringBuilder();
            for (JsonNode part : cand.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
            }
            if (text.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI returned no content.");
            return Sql.JSON.readValue(text.toString(), type);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI returned an unreadable answer.");
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI service.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Interrupted");
        }
    }
}

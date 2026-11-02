package de.bwjobs.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Thin wrapper around the official Anthropic Java SDK. Every call returns a typed record via
 * structured outputs. Server-side refusal fallback is enabled, so a refused request is retried
 * on a fallback model chosen by the API.
 */
@Component
public class ClaudeClient implements AiClient {
    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final AnthropicClient client;
    private final AppProperties.Ai cfg;

    public ClaudeClient(AppProperties props) {
        this.cfg = props.ai();
        if (cfg.enabled()) {
            AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(cfg.apiKey()).timeout(Duration.ofMinutes(5));
            if (cfg.baseUrl() != null && !cfg.baseUrl().isBlank()) b.baseUrl(cfg.baseUrl());
            this.client = b.build();
        } else {
            this.client = null;
        }
    }

    @Override
    public boolean enabled() {
        return client != null;
    }

    @Override
    public String provider() {
        return "claude";
    }

    @Override
    public String model() {
        return cfg.model();
    }

    /**
     * Sends one request and returns the parsed structured output.
     *
     * @param userId unused here; the quota is charged by {@link AiRouter}
     */
    @Override
    public <T> T structured(Long userId, String system, String userMessage, Class<T> type, long maxTokens) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI features are not configured on this server (ANTHROPIC_API_KEY missing).");
        }

        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(maxTokens)
                .system(system)
                .outputConfig(type)
                .addUserMessage(userMessage)
                .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        try {
            StructuredMessage<T> msg = client.messages().create(params);
            if (msg.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                String why = msg.stopDetails().map(d -> String.valueOf(d.explanation().orElse(""))).orElse("");
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The AI declined this request. " + why);
            }
            if (msg.stopReason().map(StopReason.MAX_TOKENS::equals).orElse(false)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI response was cut off. Please try again.");
            }
            return msg.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(t -> t.text())
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "The AI returned no content."));
        } catch (RateLimitException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service is busy. Please try again in a minute.");
        } catch (AnthropicServiceException e) {
            log.warn("Claude API error {}: {}", e.statusCode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI service error (" + e.statusCode() + ").");
        } catch (AnthropicIoException e) {
            log.warn("Claude API connection error: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach the AI service.");
        }
    }
}

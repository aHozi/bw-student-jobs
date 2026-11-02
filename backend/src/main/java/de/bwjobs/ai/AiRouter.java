package de.bwjobs.ai;

import de.bwjobs.common.ApiException;
import de.bwjobs.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The AiClient every feature uses. Picks Claude or Gemini from app.ai.provider (auto / claude / gemini)
 * and charges the per-user daily quota once, whichever provider answers.
 */
@Component
@Primary
public class AiRouter implements AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiRouter.class);

    private final AiClient selected;
    private final AiQuota quota;

    public AiRouter(ClaudeClient claude, GeminiClient gemini, AiQuota quota, AppProperties props) {
        this.quota = quota;
        String p = props.ai().provider() == null ? "auto" : props.ai().provider().trim().toLowerCase();
        this.selected = switch (p) {
            case "claude" -> claude;
            case "gemini" -> gemini;
            default -> claude.enabled() ? claude : gemini.enabled() ? gemini : claude;
        };
        log.info("AI provider: {} ({}), enabled={}", selected.provider(), selected.model(), selected.enabled());
    }

    @Override
    public boolean enabled() {
        return selected.enabled();
    }

    @Override
    public String provider() {
        return selected.provider();
    }

    @Override
    public String model() {
        return selected.model();
    }

    @Override
    public <T> T structured(Long userId, String system, String userMessage, Class<T> type, long maxTokens) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI features are not configured on this server (set ANTHROPIC_API_KEY or GEMINI_API_KEY).");
        }
        if (userId != null) quota.consume(userId);
        return selected.structured(userId, system, userMessage, type, maxTokens);
    }
}

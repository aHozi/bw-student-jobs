package de.bwjobs.ai;

/** One structured AI call: system prompt + user message → a typed record. Implemented for Claude and Gemini. */
public interface AiClient {
    boolean enabled();

    /** "claude" or "gemini". */
    String provider();

    String model();

    /**
     * @param userId user to charge against the daily quota, or null for system tasks
     */
    <T> T structured(Long userId, String system, String userMessage, Class<T> type, long maxTokens);
}

package de.bwjobs.ai;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads system prompts from src/main/resources/prompts. */
public final class Prompts {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Prompts() {}

    public static String get(String name) {
        return CACHE.computeIfAbsent(name, n -> {
            try {
                return new String(new ClassPathResource("prompts/" + n + ".txt").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Wraps untrusted text in a tag, neutralising any closing tag inside it. */
    public static String block(String tag, String content) {
        String safe = content == null ? "" : content.replace("</" + tag + ">", "</ " + tag + ">");
        return "<" + tag + ">\n" + safe + "\n</" + tag + ">\n";
    }

    public static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n[...truncated]";
    }
}

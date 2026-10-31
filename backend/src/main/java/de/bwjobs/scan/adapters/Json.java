package de.bwjobs.scan.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.common.Sql;
import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.FetchResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

final class Json {
    private Json() {}

    static JsonNode parse(FetchResult r) {
        if (!r.ok()) throw new FetchException("HTTP " + r.status() + " for " + r.finalUrl(), r.status());
        try {
            return Sql.JSON.readTree(r.body());
        } catch (Exception e) {
            throw new FetchException("Invalid JSON from " + r.finalUrl(), e);
        }
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignored) {}
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {}
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {}
        return null;
    }

    static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v.trim());
        return out;
    }
}

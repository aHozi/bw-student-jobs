package de.bwjobs.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.postgresql.util.PGobject;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Small helpers for PostgreSQL arrays, jsonb and timestamps with plain JDBC. */
public final class Sql {
    public static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .findAndAddModules()
            .build();

    private Sql() {}

    public static List<String> textArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        Object[] values = (Object[]) a.getArray();
        List<String> out = new ArrayList<>(values.length);
        for (Object v : values) if (v != null) out.add(v.toString());
        return out;
    }

    public static List<Long> longArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        Object[] values = (Object[]) a.getArray();
        List<Long> out = new ArrayList<>(values.length);
        for (Object v : values) if (v != null) out.add(((Number) v).longValue());
        return out;
    }

    /** Builds a PostgreSQL array literal like {"a","b"} usable with ?::text[] casts. */
    public static String arrayLiteral(Collection<?> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Object v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    public static String arrayLiteral(String... values) {
        return arrayLiteral(Arrays.asList(values));
    }

    public static PGobject jsonb(Object value) {
        try {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(value == null ? null : (value instanceof String s ? s : JSON.writeValueAsString(value)));
            return o;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize json", e);
        }
    }

    public static <T> T readJson(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse json", e);
        }
    }

    public static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse json", e);
        }
    }

    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize json", e);
        }
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Boolean boolOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }
}

package de.bwjobs.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a Gemini responseSchema (OpenAPI subset: OBJECT / STRING / INTEGER / NUMBER / BOOLEAN / ARRAY)
 * from the same records that define the Claude structured outputs (see AiModels).
 */
final class GeminiSchema {
    private GeminiSchema() {}

    static Map<String, Object> of(Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() == List.class) {
            return map("type", "ARRAY", "items", of(pt.getActualTypeArguments()[0]));
        }
        Class<?> c = (Class<?>) type;
        if (c == String.class) return map("type", "STRING");
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class) return map("type", "INTEGER");
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) return map("type", "NUMBER");
        if (c == boolean.class || c == Boolean.class) return map("type", "BOOLEAN");
        if (c.isRecord()) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (RecordComponent rc : c.getRecordComponents()) {
                Map<String, Object> s = new LinkedHashMap<>(of(rc.getGenericType()));
                // The annotation cannot target record components, so Java places it on the accessor and field.
                JsonPropertyDescription d = rc.getAccessor().getAnnotation(JsonPropertyDescription.class);
                if (d != null) s.put("description", d.value());
                props.put(rc.getName(), s);
                names.add(rc.getName());
            }
            return map("type", "OBJECT", "properties", props, "required", names, "propertyOrdering", names);
        }
        throw new IllegalArgumentException("Unsupported schema type: " + type);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}

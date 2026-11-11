package dev.holoweb.troupe.lua;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Network values: the Java side of {@link Values} (null, Boolean, Long/Double, String, List, Map) as JSON text. */
final class Json {
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Json() {}

    static String encode(Object value) {
        return GSON.toJson(tree(value));
    }

    private static JsonElement tree(Object v) {
        if (v == null) return com.google.gson.JsonNull.INSTANCE;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("NaN and infinity can't be sent");
            return new JsonPrimitive(n);
        }
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof List<?> list) {
            JsonArray a = new JsonArray(list.size());
            for (Object e : list) a.add(tree(e));
            return a;
        }
        if (v instanceof Map<?, ?> map) {
            JsonObject o = new JsonObject();
            map.forEach((k, e) -> o.add(String.valueOf(k), tree(e)));
            return o;
        }
        throw new IllegalArgumentException("functions can't be sent over the network");
    }

    /** @throws IllegalArgumentException on malformed JSON (it can come from the other side) */
    static Object decode(String json) {
        if (json.isEmpty()) return null;
        try {
            return value(JsonParser.parseString(json), 0);
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("malformed JSON");
        }
    }

    private static Object value(JsonElement e, int depth) {
        if (depth > 32) throw new IllegalArgumentException("value nested too deep");
        if (e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            double d = p.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) < 0x1p53 ? (Object) (long) d : (Object) d;
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) list.add(value(item, depth + 1));
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) map.put(entry.getKey(), value(entry.getValue(), depth + 1));
        return map;
    }
}

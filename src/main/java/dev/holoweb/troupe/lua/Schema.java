package dev.holoweb.troupe.lua;

import java.util.List;
import java.util.Map;

/**
 * Shape of what a client may send to a {@code net.handle}: built from a Lua table such as
 * {@code { valor = 'number', nota = 'string?', itens = { 'string' }, pos = { x = 'number', y = 'number' } }}.
 * <p>
 * Types: {@code string}, {@code number}, {@code integer}, {@code boolean}, {@code table} (anything table-like),
 * {@code any}; a trailing {@code ?} makes the field optional. A nested table is an object with those fields; a
 * table with one entry is a list of that type. Fields the schema doesn't name are rejected.
 */
final class Schema {
    private final Object root;

    private Schema(Object root) {
        this.root = root;
    }

    /** @throws IllegalArgumentException if the schema itself is malformed */
    static Schema of(Object luaSchema) {
        check(luaSchema, "schema");
        return new Schema(luaSchema);
    }

    private static void check(Object s, String where) {
        if (s instanceof String type) {
            String t = type.endsWith("?") ? type.substring(0, type.length() - 1) : type;
            if (!List.of("string", "number", "integer", "boolean", "table", "any").contains(t))
                throw new IllegalArgumentException(where + ": unknown type '" + type + "'");
        } else if (s instanceof List<?> list) {
            if (list.size() != 1) throw new IllegalArgumentException(where + ": a list schema has exactly one element type");
            check(list.get(0), where + "[]");
        } else if (s instanceof Map<?, ?> map) {
            map.forEach((k, v) -> check(v, where + "." + k));
        } else {
            throw new IllegalArgumentException(where + ": expected a type name or a table");
        }
    }

    /** @throws IllegalArgumentException with the first mismatch, e.g. {@code valor: number expected, got string} */
    void validate(Object args) {
        // An empty Lua table converts to an empty map; nil args count as an empty object.
        validate(root, args == null && root instanceof Map ? Map.of() : args, "args");
    }

    private static void validate(Object schema, Object v, String where) {
        if (schema instanceof String type) {
            boolean optional = type.endsWith("?");
            String t = optional ? type.substring(0, type.length() - 1) : type;
            if (v == null) {
                if (optional || t.equals("any")) return;
                throw new IllegalArgumentException(where + ": " + t + " expected, got nil");
            }
            boolean ok = switch (t) {
                case "string" -> v instanceof String;
                case "number" -> v instanceof Number;
                case "integer" -> v instanceof Long;
                case "boolean" -> v instanceof Boolean;
                case "table" -> v instanceof Map || v instanceof List;
                default -> true;
            };
            if (!ok) throw new IllegalArgumentException(where + ": " + t + " expected, got " + typeName(v));
        } else if (schema instanceof List<?> list) {
            // An empty table can't tell list from object apart.
            if (v instanceof Map<?, ?> m && m.isEmpty()) return;
            if (!(v instanceof List<?> items)) throw new IllegalArgumentException(where + ": list expected, got " + typeName(v));
            for (int i = 0; i < items.size(); i++) validate(list.get(0), items.get(i), where + "[" + (i + 1) + "]");
        } else {
            Map<?, ?> fields = (Map<?, ?>) schema;
            if (v instanceof List<?> l && l.isEmpty()) v = Map.of();
            if (!(v instanceof Map<?, ?> obj)) throw new IllegalArgumentException(where + ": table expected, got " + typeName(v));
            for (Object key : obj.keySet())
                if (!fields.containsKey(key)) throw new IllegalArgumentException(where + ": unexpected field '" + key + "'");
            for (Map.Entry<?, ?> f : fields.entrySet()) validate(f.getValue(), obj.get(f.getKey()), where + "." + f.getKey());
        }
    }

    private static String typeName(Object v) {
        return v == null ? "nil" : v instanceof Number ? "number" : v instanceof String ? "string"
                : v instanceof Boolean ? "boolean" : "table";
    }
}

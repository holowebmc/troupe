package dev.holoweb.troupe.api;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Arguments of a {@link ModuleFunction} call, already converted from Lua. Indexes start at 0. */
public final class Args {
    private final String resource;
    private final Side side;
    private final List<Object> values;
    private final BiFunction<Object, Class<?>, Object> convert;

    /** Created by Troupe. */
    public Args(String resource, Side side, List<Object> values, BiFunction<Object, Class<?>, Object> convert) {
        this.resource = resource;
        this.side = side;
        this.values = values;
        this.convert = convert;
    }

    /** Created by Troupe. */
    public Args(String resource, Side side, List<Object> values) {
        this.resource = resource;
        this.side = side;
        this.values = values;
        this.convert = (v, t) -> t.isInstance(v) ? v : null;
    }

    /** Name of the resource whose script made the call. */
    public String resource() {
        return resource;
    }

    public Side side() {
        return side;
    }

    public int count() {
        return values.size();
    }

    public Object get(int i) {
        return i < values.size() ? values.get(i) : null;
    }

    public String string(int i) {
        return expect(i, String.class, "string");
    }

    public double number(int i) {
        return expect(i, Number.class, "number").doubleValue();
    }

    public long integer(int i) {
        double d = number(i);
        if (d != Math.rint(d)) throw bad(i, "integer", get(i));
        return (long) d;
    }

    public boolean bool(int i) {
        return expect(i, Boolean.class, "boolean");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> table(int i) {
        return expect(i, Map.class, "table");
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(int i) {
        return expect(i, List.class, "list");
    }

    public Callback callback(int i) {
        return expect(i, Callback.class, "function");
    }

    /**
     * The argument as {@code type}: an object of a {@link Modules#type registered type}, or what a
     * {@link Modules#convert converter} makes of it (also enums by name).
     */
    public <T> T as(int i, Class<T> type) {
        Object v = get(i);
        Object out = v == null ? null : convert.apply(v, type);
        if (out == null) throw bad(i, type.getSimpleName(), v);
        return type.cast(out);
    }

    public String optString(int i, String fallback) {
        return get(i) == null ? fallback : string(i);
    }

    public double optNumber(int i, double fallback) {
        return get(i) == null ? fallback : number(i);
    }

    public boolean optBool(int i, boolean fallback) {
        return get(i) == null ? fallback : bool(i);
    }

    private <T> T expect(int i, Class<T> type, String name) {
        Object v = get(i);
        if (!type.isInstance(v)) throw bad(i, name, v);
        return type.cast(v);
    }

    private static IllegalArgumentException bad(int i, String expected, Object got) {
        String gotName = got == null ? "nil" : got instanceof Number ? "number" : got instanceof String ? "string"
                : got instanceof Boolean ? "boolean" : got instanceof Callback ? "function" : got instanceof Map || got instanceof List ? "table" : got.getClass().getSimpleName();
        return new IllegalArgumentException("bad argument #" + (i + 1) + " (" + expected + " expected, got " + gotName + ")");
    }
}

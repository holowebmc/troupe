package dev.holoweb.troupe.api;

import java.util.List;
import java.util.Map;

/** Arguments of a {@link ModuleFunction} call, already converted from Lua. Indexes start at 0. */
public final class Args {
    private final String resource;
    private final Side side;
    private final List<Object> values;

    /** Created by Troupe. */
    public Args(String resource, Side side, List<Object> values) {
        this.resource = resource;
        this.side = side;
        this.values = values;
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
                : got instanceof Boolean ? "boolean" : got instanceof Callback ? "function" : "table";
        return new IllegalArgumentException("bad argument #" + (i + 1) + " (" + expected + " expected, got " + gotName + ")");
    }
}

package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Callback;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A declared signature, written the way the Lua language server writes types:
 * {@code (amount: integer, note?: string, ...: any) -> integer}. It checks the arguments of a call and becomes the
 * {@code ---@param} / {@code ---@return} lines of the editor definitions.
 * <p>
 * Checked types: {@code string number integer boolean table function any nil}, {@code Player} and {@code Entity}
 * (a handle or its id), the name of a registered type, {@code T[]} (a list; its items aren't checked), a quoted
 * literal, and unions with {@code |}. A trailing {@code ?} on the name or the type accepts nil. Any other name
 * (an alias such as {@code Pos}, an inline {@code { x: number }}) is for the editor only: any value passes.
 */
final class Sig {
    record Param(String name, String type, boolean optional) {}

    final List<Param> params;
    final boolean varargs;
    /** The text after {@code ->}, or null. */
    final String returns;

    private Sig(List<Param> params, boolean varargs, String returns) {
        this.params = params;
        this.varargs = varargs;
        this.returns = returns;
    }

    /** @throws IllegalArgumentException when the text isn't a signature */
    static Sig parse(String text) {
        String s = text.trim();
        int close = closing(s);
        if (!s.startsWith("(") || close < 0) throw new IllegalArgumentException("bad signature '" + text + "': expected (name: type, ...) -> type");
        String rest = s.substring(close + 1).trim();
        if (!rest.isEmpty() && !rest.startsWith("->")) throw new IllegalArgumentException("bad signature '" + text + "': expected -> before the return type");
        String returns = rest.isEmpty() ? null : rest.substring(2).trim();
        List<Param> params = new ArrayList<>();
        boolean varargs = false;
        for (String part : split(s.substring(1, close), ',')) {
            if (part.isEmpty()) continue;
            if (varargs) throw new IllegalArgumentException("bad signature '" + text + "': ... comes last");
            int colon = part.indexOf(':');
            String name = (colon < 0 ? part : part.substring(0, colon)).trim(), type = colon < 0 ? "any" : part.substring(colon + 1).trim();
            if (name.equals("...")) {
                varargs = true;
                continue;
            }
            boolean optional = name.endsWith("?");
            if (optional) name = name.substring(0, name.length() - 1);
            if (type.endsWith("?")) {
                optional = true;
                type = type.substring(0, type.length() - 1);
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || type.isEmpty()) throw new IllegalArgumentException("bad signature '" + text + "' at '" + part + "'");
            params.add(new Param(name, type, optional));
        }
        return new Sig(List.copyOf(params), varargs, returns == null || returns.isEmpty() ? null : returns);
    }

    /** What reflection knows of an annotated method. */
    static Sig of(Method m, ModuleRegistry registry) {
        List<Param> params = new ArrayList<>();
        boolean varargs = false;
        for (var p : m.getParameters()) {
            if (p.getType() == Args.class) varargs = true;
            else params.add(new Param(p.getName(), Annotated.luaType(p.getType(), registry), !p.getType().isPrimitive()));
        }
        String returns = m.getReturnType() == void.class ? null : Annotated.async(m) ? "any" : Annotated.luaType(m.getReturnType(), registry);
        return new Sig(params, varargs, returns);
    }

    /** Names only: all an unannotated Lua function tells. */
    static Sig named(List<String> names, boolean varargs) {
        return new Sig(names.stream().map(n -> new Param(n, "any", true)).toList(), varargs, null);
    }

    private static int closing(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{' || c == '[' || c == '<') depth++;
            else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '>') i++; // the arrow of a fun() type
            else if (c == ')' || c == '}' || c == ']' || c == '>') if (--depth == 0) return i;
        }
        return -1;
    }

    /** Splits at {@code sep} outside brackets and quotes. */
    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '{' || c == '[' || c == '<') depth++;
            else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '>') i++;
            else if (c == ')' || c == '}' || c == ']' || c == '>') depth--;
            else if (c == sep && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        return out;
    }

    /** @throws IllegalArgumentException naming the first argument that doesn't fit: {@code bad argument #2 'note' (string expected, got number)} */
    void check(int count, IntFunction<Object> arg, ModuleRegistry registry) {
        for (int i = 0; i < params.size(); i++) {
            Param p = params.get(i);
            Object v = i < count ? arg.apply(i) : null;
            if (v == null ? !p.optional() && !fits(p.type(), null, registry) : !fits(p.type(), v, registry))
                throw new IllegalArgumentException("bad argument #" + (i + 1) + " '" + p.name() + "' (" + p.type() + " expected, got " + typeName(v, registry) + ")");
        }
        if (!varargs && count > params.size() && arg.apply(params.size()) != null)
            throw new IllegalArgumentException("too many arguments (" + params.size() + " expected, got " + count + ")");
    }

    void check(Args a, int from, ModuleRegistry registry) {
        check(Math.max(0, a.count() - from), i -> a.get(from + i), registry);
    }

    static boolean fits(String type, Object v, ModuleRegistry registry) {
        String t = type.trim();
        if (t.endsWith("?")) return v == null || fits(t.substring(0, t.length() - 1), v, registry);
        List<String> union = split(t, '|');
        if (union.size() > 1) {
            for (String u : union) if (fits(u, v, registry)) return true;
            return false;
        }
        if (t.equals("any")) return true;
        if (v == null) return t.equals("nil");
        if (t.endsWith("[]")) return v instanceof List || v instanceof Map<?, ?> m && m.isEmpty();
        if (t.length() > 1 && (t.charAt(0) == '"' || t.charAt(0) == '\'')) return v.equals(t.substring(1, t.length() - 1));
        switch (t) {
            case "string": return v instanceof String;
            case "number": return v instanceof Number;
            case "integer": return v instanceof Long || v instanceof Integer;
            case "boolean": return v instanceof Boolean;
            case "table": return v instanceof Map || v instanceof List;
            case "function": return v instanceof Callback;
            case "nil": return false;
            case "Player": case "Entity": return v instanceof String || v instanceof Peer || v instanceof EntityRef || v instanceof Map<?, ?> m && m.get("id") instanceof String;
            default:
        }
        if (t.startsWith("fun(")) return v instanceof Callback;
        if (!registry.hasType(t)) return true; // an alias or a shape: the editor's business
        ModuleRegistry.TypeImpl<?> actual = registry.typeOf(v.getClass());
        return actual != null && actual.name.equals(t);
    }

    static String typeName(Object v, ModuleRegistry registry) {
        if (v == null) return "nil";
        if (v instanceof Number) return v instanceof Long || v instanceof Integer ? "integer" : "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Callback) return "function";
        if (v instanceof Map || v instanceof List) return "table";
        ModuleRegistry.TypeImpl<?> type = registry.typeOf(v.getClass());
        return type != null ? type.name : v.getClass().getSimpleName();
    }

    // ---- editor definitions

    /** {@code fun(a: integer, b?: string): boolean}, the type of a function with this signature. */
    String funType() {
        StringBuilder out = new StringBuilder("fun(");
        for (Param p : params) out.append(out.length() > 4 ? ", " : "").append(p.name()).append(p.optional() ? "?" : "").append(": ").append(p.type());
        if (varargs) out.append(out.length() > 4 ? ", " : "").append("...: any");
        return out.append(')').append(returns == null ? "" : ": " + returns).toString();
    }

    /** The annotated declaration {@code function owner<sep>name(a, b) end}. */
    void declare(StringBuilder out, String owner, char sep, String name) {
        StringBuilder names = new StringBuilder();
        for (Param p : params) {
            out.append("---@param ").append(p.name()).append(p.optional() ? "? " : " ").append(p.type()).append('\n');
            names.append(names.isEmpty() ? "" : ", ").append(p.name());
        }
        if (varargs) {
            out.append("---@param ... any\n");
            names.append(names.isEmpty() ? "" : ", ").append("...");
        }
        if (returns != null) out.append("---@return ").append(returns).append('\n');
        out.append("function ").append(owner).append(sep).append(name).append('(').append(names).append(") end\n");
    }

    /** With no signature known, the function takes anything. */
    static void declare(StringBuilder out, String owner, char sep, String name, Sig sig) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) return;
        if (sig != null) sig.declare(out, owner, sep, name);
        else out.append("function ").append(owner).append(sep).append(name).append("(...) end\n");
    }
}

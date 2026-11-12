package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Callback;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaString;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LuaFunction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lua ↔ Java conversion for module calls (see {@link dev.holoweb.troupe.api.Module}). */
final class Values {
    private static final int MAX_DEPTH = 32;

    private Values() {}

    // Lua strings are bytes and Cobalt maps them to Java chars as Latin-1; scripts are UTF-8, so convert explicitly.

    /** A Lua value as text, strings decoded as UTF-8. */
    static String str(LuaValue v) {
        if (!(v instanceof LuaString s)) return v.toString();
        byte[] bytes = new byte[s.length()];
        s.copyTo(bytes, 0);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static LuaString lua(String s) {
        return ValueFactory.valueOf(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Fixes text Cobalt decoded as Latin-1 (error messages); leaves real Unicode alone. */
    static String readable(String latin1) {
        for (int i = 0; i < latin1.length(); i++) if (latin1.charAt(i) > 0xFF) return latin1;
        return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    static List<Object> toJava(LuaRuntime runtime, Varargs args, int from) throws LuaError {
        List<Object> out = new ArrayList<>(Math.max(0, args.count() - from + 1));
        for (int i = from; i <= args.count(); i++) out.add(toJava(runtime, args.arg(i), 0));
        return out;
    }

    static Object toJava(LuaRuntime runtime, LuaValue v, int depth) throws LuaError {
        switch (v.type()) {
            case Constants.TNIL:
                return null;
            case Constants.TBOOLEAN:
                return v.toBoolean();
            case Constants.TNUMBER: {
                double d = v.toDouble();
                return d == Math.rint(d) && Math.abs(d) < 0x1p53 ? (Object) (long) d : (Object) d;
            }
            case Constants.TSTRING:
                return str(v);
            case Constants.TFUNCTION:
                return runtime.callback((LuaFunction) v);
            case Constants.TTABLE: {
                if (depth >= MAX_DEPTH) throw new LuaError("table nested too deep (or cyclic)");
                LuaTable t = (LuaTable) v;
                int n = t.length();
                if (n > 0 && t.size() == n) { // a list: keys 1..n and nothing else
                    List<Object> list = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) list.add(toJava(runtime, t.rawget(i), depth + 1));
                    return list;
                }
                Map<String, Object> map = new LinkedHashMap<>();
                for (Varargs e = t.next(Constants.NIL); !e.first().isNil(); e = t.next(e.first()))
                    map.put(str(e.first()), toJava(runtime, e.arg(2), depth + 1));
                return map;
            }
            default:
                throw new LuaError(v.typeName() + " values can't be passed to Java");
        }
    }

    static LuaValue toLua(LuaRuntime runtime, Object o) throws LuaError {
        return toLua(runtime, o, 0);
    }

    private static LuaValue toLua(LuaRuntime runtime, Object o, int depth) throws LuaError {
        if (depth >= MAX_DEPTH) throw new LuaError("value nested too deep (or cyclic)");
        if (o == null) return Constants.NIL;
        if (o instanceof Boolean b) return ValueFactory.valueOf(b);
        if (o instanceof Number n) return ValueFactory.valueOf(n.doubleValue());
        if (o instanceof CharSequence s) return lua(s.toString());
        if (o instanceof Peer p) return runtime.net.player(p);
        if (o instanceof EntityRef e) {
            LuaTable t = new LuaTable();
            t.rawset("id", lua(e.id()));
            t.rawset("type", lua(e.type()));
            LuaTable meta = new LuaTable();
            meta.rawset("__index", runtime.entityMethods);
            t.setMetatable(null, meta);
            return t;
        }
        if (o instanceof LuaRuntime.LuaCallback c) {
            if (c.runtime() != runtime) throw new LuaError("a function from another resource can't be passed here");
            return c.function();
        }
        if (o instanceof Map<?, ?> m) {
            LuaTable t = new LuaTable(0, m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) continue;
                t.rawset(toLua(runtime, e.getKey(), depth + 1), toLua(runtime, e.getValue(), depth + 1));
            }
            return t;
        }
        if (o instanceof Iterable<?> list) {
            LuaTable t = new LuaTable();
            int i = 1;
            for (Object e : list) t.rawset(i++, toLua(runtime, e, depth + 1));
            return t;
        }
        if (o instanceof Object[] array) return toLua(runtime, Arrays.asList(array), depth);
        if (o instanceof Callback) throw new LuaError("a function from another resource can't be passed here");
        throw new LuaError(o.getClass().getSimpleName() + " values can't be passed to Lua");
    }
}

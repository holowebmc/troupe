package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Callback;
import dev.holoweb.troupe.api.LuaFunction;
import dev.holoweb.troupe.api.LuaProperty;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** {@link LuaFunction} / {@link LuaProperty} members turned into module functions and type members, by reflection. */
final class Annotated {
    private Annotated() {}

    interface Call {
        Object call(Object self, Args args) throws Exception;
    }

    static String name(Method m) {
        String n = m.getAnnotation(LuaFunction.class).value();
        return n.isEmpty() ? m.getName() : n;
    }

    static boolean async(Method m) {
        return CompletionStage.class.isAssignableFrom(m.getReturnType());
    }

    static Call call(Method m) {
        m.trySetAccessible();
        Class<?>[] types = m.getParameterTypes();
        return (self, a) -> {
            Object[] in = new Object[types.length];
            int at = 0;
            for (int i = 0; i < types.length; i++) in[i] = types[i] == Args.class ? a : arg(a, at++, types[i]);
            try {
                return m.invoke(self, in);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            }
        };
    }

    static Object arg(Args a, int i, Class<?> t) {
        if (a.get(i) == null && !t.isPrimitive()) return null;
        if (t == String.class) return a.string(i);
        if (t == long.class || t == Long.class) return a.integer(i);
        if (t == int.class || t == Integer.class) {
            long v = a.integer(i);
            if (v != (int) v) throw new IllegalArgumentException("bad argument #" + (i + 1) + " (number too big)");
            return (int) v;
        }
        if (t == double.class || t == Double.class) return a.number(i);
        if (t == float.class || t == Float.class) return (float) a.number(i);
        if (t == boolean.class || t == Boolean.class) return a.bool(i);
        if (t == Map.class) return a.table(i);
        if (t == List.class) return a.list(i);
        if (t == Callback.class) return a.callback(i);
        if (t == Object.class) return a.get(i);
        return a.as(i, t);
    }

    /** Fills a type from the annotations on its class (public members, inherited ones too). */
    static <T> void scan(ModuleRegistry.TypeImpl<T> type, ModuleRegistry registry) {
        for (Method m : type.type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.isAnnotationPresent(LuaFunction.class)) {
                if (async(m)) throw new IllegalArgumentException(m + ": methods of a type can't wait; make it a module function");
                Call call = call(m);
                type.method(name(m), call::call);
                type.reflected.put(name(m), m);
            }
            LuaProperty p = m.getAnnotation(LuaProperty.class);
            if (p == null) continue;
            String name = p.value().isEmpty() ? m.getName() : p.value();
            m.trySetAccessible();
            if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                type.getters.put(name, o -> get(() -> m.invoke(o)));
                type.propertyTypes.put(name, m.getReturnType());
            } else if (m.getParameterCount() == 1) {
                Class<?> t = m.getParameterTypes()[0];
                type.setters.put(name, (o, v) -> get(() -> m.invoke(o, arg(registry.args(v), 0, t))));
            } else throw new IllegalArgumentException(m + ": a property is a getter without parameters or a setter with one");
        }
        for (Field f : type.type.getFields()) {
            LuaProperty p = f.getAnnotation(LuaProperty.class);
            if (p == null || Modifier.isStatic(f.getModifiers())) continue;
            String name = p.value().isEmpty() ? f.getName() : p.value();
            f.trySetAccessible();
            type.getters.put(name, o -> get(() -> f.get(o)));
            type.propertyTypes.put(name, f.getType());
            if (!Modifier.isFinal(f.getModifiers())) type.setters.put(name, (o, v) -> get(() -> {
                f.set(o, arg(registry.args(v), 0, f.getType()));
                return null;
            }));
        }
    }

    private interface Reflective {
        Object run() throws ReflectiveOperationException;
    }

    /** Property access can't declare exceptions: what the member throws goes up as it is. */
    private static Object get(Reflective r) {
        try {
            return r.run();
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- LuaLS definitions

    static String luaType(Class<?> t, ModuleRegistry registry) {
        if (t == void.class || t == Void.class) return "nil";
        if (t == String.class || t == java.util.UUID.class || t.isEnum()) return "string";
        if (t == boolean.class || t == Boolean.class) return "boolean";
        if (t == long.class || t == Long.class || t == int.class || t == Integer.class) return "integer";
        if (t.isPrimitive() || Number.class.isAssignableFrom(t)) return "number";
        if (Map.class.isAssignableFrom(t)) return "table";
        if (Iterable.class.isAssignableFrom(t) || t.isArray()) return "any[]";
        if (t == Callback.class) return "function";
        if (t == Peer.class) return "Player";
        if (t == EntityRef.class) return "Entity";
        String named = registry.luaName(t);
        if (named != null) return named;
        ModuleRegistry.TypeImpl<?> type = registry.typeOf(t);
        return type != null && registry.converter(t) == null ? type.name : "any";
    }


}

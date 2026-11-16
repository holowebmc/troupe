package dev.holoweb.troupe.lua;

import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaUserdata;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The server's {@code java} global: reflection into the packages the server owner allows in {@code config/troupe.json}.
 * Never on the client. Off (every call errors) while the list is empty.
 * <pre>
 * local Math = java.class('java.lang.Math')   Math.max(2, 3)   Math.PI
 * local list = java.new('java.util.ArrayList')   list:add('a')   list:size()   tostring(list)
 * </pre>
 * Strings, numbers, booleans and nil convert both ways; anything else is a handle whose public methods and fields
 * are reachable while its class is allowed. Some classes are refused even when their package is allowed (class
 * loading, reflection, processes, threads, files): {@link #DENIED}.
 * ponytail: overloads are tried in reflection order, first whose parameters accept the arguments wins.
 */
final class JavaApi {
    static final List<String> DENIED = List.of("java.lang.Class", "java.lang.ClassLoader", "java.lang.Runtime",
            "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessHandle", "java.lang.System",
            "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.reflect.", "java.lang.invoke.", "java.lang.ref.",
            "java.lang.foreign.", "java.lang.module.", "java.io.", "java.nio.file.", "java.nio.channels.", "java.net.",
            "sun.", "jdk.", "com.sun.", "net.fabricmc.loader.", "org.spongepowered.",
            "net.neoforged.fml.", "net.neoforged.fancymodloader.", "cpw.mods.");

    record Handle(Object object, boolean isClass) {}

    private final LuaRuntime rt;
    private final List<String> allow;
    private final LuaTable meta = new LuaTable();

    JavaApi(LuaRuntime rt, LuaTable globals, List<String> allow) {
        this.rt = rt;
        this.allow = List.copyOf(allow);
        meta.rawset("__index", LibFunction.create((s, self, key) -> index((Handle) ((LuaUserdata) self).instance, key.checkString())));
        meta.rawset("__tostring", LibFunction.create((s, self) -> Values.lua(String.valueOf(((Handle) ((LuaUserdata) self).instance).object()))));
        meta.rawset("__metatable", Values.lua("java")); // scripts can't swap it

        LuaTable java = new LuaTable();
        java.rawset("class", LibFunction.create((s, name) -> handle(load(name.checkString()), true)));
        java.rawset("new", LibFunction.createV((s, a) -> {
            Class<?> c = a.arg(1) instanceof LuaUserdata u && u.instance instanceof Handle h && h.isClass()
                    ? (Class<?>) h.object() : load(a.arg(1).checkString());
            List<Executable> ctors = new ArrayList<>(Arrays.asList(c.getConstructors()));
            return invoke(c.getName() + ".new", ctors, null, a, 2);
        }));
        globals.rawset("java", java);
    }

    private void on() throws LuaError {
        if (allow.isEmpty()) throw new LuaError("java.* is off on this server: list the allowed packages in config/troupe.json");
    }

    boolean allowed(Class<?> c) {
        String name = c.getName();
        for (String d : DENIED) if (d.endsWith(".") ? name.startsWith(d) : name.equals(d) || name.startsWith(d + "$")) return false;
        for (String a : allow) if (name.equals(a) || name.startsWith(a + ".") || name.startsWith(a + "$")) return true;
        return false;
    }

    private Class<?> load(String name) throws LuaError {
        on();
        Class<?> c;
        try {
            c = Class.forName(name, false, JavaApi.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new LuaError("no class " + name);
        }
        if (!allowed(c)) throw new LuaError("class " + name + " isn't allowed (config/troupe.json)");
        return c;
    }

    private LuaValue handle(Object o, boolean isClass) {
        return ValueFactory.userdataOf(new Handle(o, isClass), meta);
    }

    /** {@code handle.key}: a method (call it with : on objects, with . on classes) or a public field's value. */
    private LuaValue index(Handle h, String key) throws LuaError {
        on();
        Class<?> c = h.isClass() ? (Class<?>) h.object() : h.object().getClass();
        if (!allowed(c)) throw new LuaError("class " + c.getName() + " isn't allowed (config/troupe.json)");
        List<Executable> methods = new ArrayList<>();
        for (Method m : c.getMethods())
            if (m.getName().equals(key) && Modifier.isStatic(m.getModifiers()) == h.isClass()) methods.add(m);
        if (!methods.isEmpty()) {
            Object target = h.isClass() ? null : h.object();
            int from = h.isClass() ? 1 : 2; // obj:m(...) passes obj first
            return LibFunction.createV((s, a) -> invoke(c.getName() + "." + key, methods, target, a, from));
        }
        try {
            Field f = c.getField(key);
            if (Modifier.isStatic(f.getModifiers()) != h.isClass()) return Constants.NIL;
            return toLua(f.get(h.isClass() ? null : h.object()));
        } catch (NoSuchFieldException e) {
            return Constants.NIL;
        } catch (IllegalAccessException e) {
            throw new LuaError("can't read " + c.getName() + "." + key);
        }
    }

    private Varargs invoke(String what, List<Executable> candidates, Object target, Varargs a, int from) throws LuaError {
        int n = Math.max(0, a.count() - from + 1);
        for (Executable e : candidates) {
            if (e.getParameterCount() != n || e.isVarArgs() && n == 0) continue;
            Object[] args = new Object[n];
            boolean fits = true;
            Class<?>[] types = e.getParameterTypes();
            for (int i = 0; i < n && fits; i++) {
                Object v = convert(a.arg(from + i), types[i]);
                if (v == MISMATCH) fits = false;
                else args[i] = v;
            }
            if (!fits) continue;
            try {
                Object result = e instanceof Method m ? accessible(m).invoke(target, args) : ((Constructor<?>) e).newInstance(args);
                return toLua(result);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new LuaError(what + ": " + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
            } catch (ReflectiveOperationException | IllegalArgumentException ex) {
                throw new LuaError(what + ": " + ex.getMessage());
            }
        }
        throw new LuaError("no overload of " + what + " takes these " + n + " argument(s)");
    }

    /** A public method of a non-public class (e.g. a JDK implementation) is called through a public supertype. */
    private static Method accessible(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) return m;
        for (Class<?> c = m.getDeclaringClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                try {
                    return i.getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (c.getSuperclass() != null && Modifier.isPublic(c.getSuperclass().getModifiers())) {
                try {
                    return c.getSuperclass().getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return m;
    }

    private static final Object MISMATCH = new Object();

    private Object convert(LuaValue v, Class<?> t) throws LuaError {
        if (v.isNil()) return t.isPrimitive() ? MISMATCH : null;
        if (v instanceof LuaUserdata u && u.instance instanceof Handle h)
            return h.isClass() ? MISMATCH : box(t).isInstance(h.object()) ? h.object() : MISMATCH;
        if (v instanceof LuaUserdata u) return box(t).isInstance(u.instance) ? u.instance : MISMATCH; // an object of a mod's type
        switch (v.type()) {
            case Constants.TBOOLEAN:
                return t == boolean.class || t == Boolean.class || t == Object.class ? (Object) v.toBoolean() : MISMATCH;
            case Constants.TNUMBER: {
                double d = v.toDouble();
                boolean whole = d == Math.rint(d);
                if ((t == int.class || t == Integer.class) && whole && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return (int) d;
                if ((t == long.class || t == Long.class) && whole && Math.abs(d) < 0x1p63) return (long) d;
                if ((t == short.class || t == Short.class) && whole && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE) return (short) d;
                if ((t == byte.class || t == Byte.class) && whole && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE) return (byte) d;
                if (t == float.class || t == Float.class) return (float) d;
                if (t == double.class || t == Double.class || t == Number.class || t == Object.class) return d;
                return MISMATCH;
            }
            case Constants.TSTRING: {
                String s = Values.str(v);
                if (t == String.class || t == CharSequence.class || t == Object.class) return s;
                if ((t == char.class || t == Character.class) && s.length() == 1) return s.charAt(0);
                return MISMATCH;
            }
            case Constants.TTABLE: {
                Object o = Values.toJava(rt, v, 0);
                if (o instanceof Map<?, ?> m && m.isEmpty() && t.isAssignableFrom(ArrayList.class)) o = new ArrayList<>(); // {} is also a list
                return t.isInstance(o) ? o : MISMATCH;
            }
            default:
                return MISMATCH;
        }
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == boolean.class) return Boolean.class;
        if (t == char.class) return Character.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return Void.class;
    }

    private LuaValue toLua(Object o) {
        if (o == null) return Constants.NIL;
        if (o instanceof Boolean b) return ValueFactory.valueOf(b);
        if (o instanceof Number n && !(o instanceof java.math.BigDecimal) && !(o instanceof java.math.BigInteger)) return ValueFactory.valueOf(n.doubleValue());
        if (o instanceof String || o instanceof Character) return Values.lua(o.toString());
        return handle(o, false);
    }
}

package dev.holoweb.troupe.api;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A Java class scripts hold as an object: {@code obj:method(...)}, {@code obj.property}, {@code obj.property = v},
 * {@code tostring(obj)} (the object's {@code toString}) and {@code a == b} ({@code equals}). Objects of the class, or
 * of a subclass, become one wherever a value goes to Lua (results, event arguments, inside tables); coming back,
 * {@link Args#as} hands the same Java object. Scripts can't build one or reach anything that isn't declared here.
 */
public interface Type<T> {
    String name();

    /** {@code obj:name(...)}; the arguments start at index 0, after the object. Runs on the game thread. */
    Type<T> method(String name, Method<T> method);

    /** {@link #method(String, Method)} with its signature declared (the arguments after the object), see {@link Module#function(String, String, ModuleFunction)}. */
    Type<T> method(String name, String signature, Method<T> method);

    /** A read-only property of a declared Lua type ("integer", "string?", another type's name). */
    Type<T> property(String name, String type, Function<? super T, Object> get);

    /** A property of a declared type; values that don't fit are refused before {@code set} runs. */
    Type<T> property(String name, String type, Function<? super T, Object> get, BiConsumer<? super T, Object> set);

    /** {@code obj.name}, read only. */
    Type<T> property(String name, Function<? super T, Object> get);

    /** {@code obj.name} and {@code obj.name = value}; {@code set} throws IllegalArgumentException on a bad value. */
    Type<T> property(String name, Function<? super T, Object> get, BiConsumer<? super T, Object> set);

    @FunctionalInterface
    interface Method<T> {
        /** A thrown exception becomes a Lua error with its message. Return null for no value. */
        Object call(T self, Args args) throws Exception;
    }
}

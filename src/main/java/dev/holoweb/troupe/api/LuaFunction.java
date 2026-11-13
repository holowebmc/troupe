package dev.holoweb.troupe.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A public method scripts can call: a function of the module ({@link Modules#module(String, Object)}) or a method of
 * the type ({@link Modules#type}). Parameters are filled from the Lua arguments in order: {@code String}, numbers,
 * {@code boolean}, {@code Map} (table), {@code List}, {@link Callback}, {@code Object} (as it comes), or anything
 * {@link Args#as} gives (registered types, converted classes, enums). Primitives are required; the others are null
 * when the script passes nil. A parameter of type {@link Args} gets all of them (and the calling resource's name).
 * Returning a {@code CompletionStage} makes it a function the script waits on ({@link Module#async}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LuaFunction {
    /** The name in Lua; the method's own when empty. */
    String value() default "";
}

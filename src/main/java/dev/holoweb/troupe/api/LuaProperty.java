package dev.holoweb.troupe.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a class given to {@link Modules#type}: {@code obj.name} in Lua. A public field (read only when final), a method
 * without parameters (the getter) or a void method with one (the setter, under the same name).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface LuaProperty {
    /** The name in Lua; the member's own when empty. */
    String value() default "";
}

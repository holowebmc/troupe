package dev.holoweb.troupe.api;

import java.util.function.Consumer;
import java.util.function.Function;

/** The modules of one side. */
public interface Modules {
    Side side();

    /** Creates the module, or returns the existing one with that name. Names: a-z, 0-9, '_' and '-'. */
    Module module(String name);

    /** The module {@code name} with every {@link LuaFunction} method of {@code api} as one of its functions. */
    Module module(String name, Object api);

    /**
     * Makes objects of {@code type} (and subclasses) reach scripts as objects with the methods and properties declared
     * on the result, besides the ones the class marks with {@link LuaFunction} and {@link LuaProperty}. {@code name} is what error messages call it.
     */
    <T> Type<T> type(Class<T> type, String name);

    /**
     * How values of {@code type} (and subclasses) go to Lua: {@code toLua} returns anything that already crosses
     * (a string, number, {@code Map}, {@code List}, another converted type...).
     */
    <T> void convert(Class<T> type, Function<? super T, Object> toLua);

    /**
     * Also the way back, for {@link Args#as}: {@code fromLua} gets the value as Lua sent it (see {@link Module}) and
     * returns null or throws IllegalArgumentException when it isn't one.
     */
    <T> void convert(Class<T> type, Function<? super T, Object> toLua, Function<Object, ? extends T> fromLua);

    /**
     * Adds {@code handle:name(...)} to every player ({@code kind} "player") or entity ("entity") handle of this side.
     * The function gets the handle's id (the UUID as text) at index 0, then the arguments. Troupe's own methods win
     * a name clash.
     */
    void method(String kind, String name, ModuleFunction function);

    /** {@link #method(String, String, ModuleFunction)} with the signature of the arguments after the handle. */
    void method(String kind, String name, String signature, ModuleFunction function);

    /** Hears the name of each resource of this side right after it started. Game thread. */
    void onStart(Consumer<String> listener);

    /** Hears the name of each resource of this side right after it stopped: drop what you kept for it. Game thread. */
    void onStop(Consumer<String> listener);
}

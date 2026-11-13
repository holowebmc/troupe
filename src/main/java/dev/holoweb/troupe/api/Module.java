package dev.holoweb.troupe.api;

/**
 * Functions and events under one name. Lua sees {@code mods.call('name', 'fn', ...)}, {@code mods.get('name').fn(...)}
 * and {@code mods.on('name', 'event', handler)}.
 * <p>
 * Values crossing the boundary: nil ↔ null, boolean, number (Lua → {@code Long} when integral, else {@code Double};
 * any {@code Number} back), string, table ↔ {@code List} (1..n) or {@code Map<String, Object>}, Lua function →
 * {@link Callback}. More types: {@link Modules#convert} and {@link Modules#type}; {@code UUID} (text), enums (their
 * name in lower case) and {@code Optional} already convert.
 */
public interface Module {
    String name();

    /** Runs on the game thread of this side, inside the calling script; its return value goes back to Lua. */
    Module function(String name, ModuleFunction function);

    /**
     * The same, with its signature declared: {@code "(amount: integer, note?: string) -> integer"}. Calls that don't fit
     * fail before {@code function} runs ("bad argument #1 'amount' (integer expected, got string)") and editors show it.
     * Types: {@code string number integer boolean table function any}, {@code Player}, {@code Entity}, the name of a
     * {@link Modules#type registered type}, {@code T[]}, unions with {@code |}; a {@code ?} accepts nil; {@code ...}
     * takes the rest. Other names and shapes ({@code Pos}, <code>{ x: number }</code>) are shown to the editor, not checked.
     */
    Module function(String name, String signature, ModuleFunction function);

    /**
     * A function that answers later (a database, another thread): the script calls it like any other and waits.
     * Not callable inside {@code coroutine.create/wrap}.
     */
    Module async(String name, AsyncFunction function);

    /** {@link #async(String, AsyncFunction)} with its signature declared, as in {@link #function(String, String, ModuleFunction)}. */
    Module async(String name, String signature, AsyncFunction function);

    /**
     * This module's version ("1.2.0"), for resources that ask for a minimum ({@code "bank >= 1.2"} in resource.json)
     * and for {@code mods.version('bank')}.
     */
    Module version(String version);

    /**
     * On the client: one line telling the player what a server's scripts can do through this module ("Read and write
     * your waypoints"), shown before they join a server whose resources use it. A translation key works too.
     */
    Module describe(String text);

    /** Declares an event resources may listen to with {@code mods.on}. */
    Module event(String name);

    /** An event with the arguments its handlers get: {@code event("paid", "(player: Player, amount: integer)")}. */
    Module event(String name, String signature);

    /**
     * Declares the event and returns a {@code type} (an interface, such as a Fabric callback) that emits it with
     * whatever arguments it is called with: {@code ServerPlayConnectionEvents.JOIN.register(m.listener("join",
     * ServerPlayConnectionEvents.Join.class))}. Arguments Lua can't hold arrive as nil. When the interface's method
     * returns {@code boolean}, the handlers run at once (so only for events of the game thread) and any that returns
     * {@code false} makes it false; otherwise delivery is the same as {@link #emit}.
     */
    <T> T listener(String event, Class<T> type);

    /** Same, for an interface whose method must return something: always {@code result} (say, {@code InteractionResult.PASS}). */
    <T> T listener(String event, Class<T> type, Object result);

    /**
     * Delivers the event to every running resource of this side that listens to it. Thread-safe: delivery happens
     * on the game thread, after the current script (if any) returns.
     */
    void emit(String event, Object... args);
}

package dev.holoweb.troupe.api;

/**
 * Functions and events under one name. Lua sees {@code mods.call('name', 'fn', ...)}, {@code mods.get('name').fn(...)}
 * and {@code mods.on('name', 'event', handler)}.
 * <p>
 * Values crossing the boundary: nil ↔ null, boolean, number (Lua → {@code Long} when integral, else {@code Double};
 * any {@code Number} back), string, table ↔ {@code List} (1..n) or {@code Map<String, Object>}, Lua function →
 * {@link Callback}.
 */
public interface Module {
    String name();

    /** Runs on the game thread of this side, inside the calling script; its return value goes back to Lua. */
    Module function(String name, ModuleFunction function);

    /** Declares an event resources may listen to with {@code mods.on}. */
    Module event(String name);

    /**
     * Delivers the event to every running resource of this side that listens to it. Thread-safe: delivery happens
     * on the game thread, after the current script (if any) returns.
     */
    void emit(String event, Object... args);
}

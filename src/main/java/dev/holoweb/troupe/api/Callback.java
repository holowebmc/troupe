package dev.holoweb.troupe.api;

/** A Lua function handed to Java. */
public interface Callback {
    /**
     * Calls the function later on the game thread (never inside the current call), and does nothing once its
     * resource has stopped. Thread-safe.
     */
    void invoke(Object... args);
}

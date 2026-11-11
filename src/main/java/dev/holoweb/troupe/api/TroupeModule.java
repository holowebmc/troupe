package dev.holoweb.troupe.api;

/**
 * How a mod exposes functions and events to Troupe resources (Lua: {@code mods.call}, {@code mods.get}, {@code mods.on}).
 * <p>
 * Declare the class in {@code fabric.mod.json} under one of the entrypoints {@code troupe} (both sides),
 * {@code troupe-client} or {@code troupe-server}. {@link #register} runs once per side: twice in singleplayer,
 * where the integrated server and the client each have their own resources and modules.
 */
@FunctionalInterface
public interface TroupeModule {
    void register(Modules modules);
}

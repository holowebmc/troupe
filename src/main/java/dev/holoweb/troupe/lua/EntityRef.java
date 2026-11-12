package dev.holoweb.troupe.lua;

/** An entity as scripts hold it: becomes {@code {id, type}} with the entity methods (prelude) in Lua. */
public record EntityRef(String id, String type) {}

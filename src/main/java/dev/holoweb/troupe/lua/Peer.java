package dev.holoweb.troupe.lua;

/** A connected player, as the server sees it. {@code id} is the UUID as text. */
public record Peer(String id, String name) {}

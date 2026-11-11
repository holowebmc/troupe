package dev.holoweb.troupe.api;

public enum Side {
    CLIENT, SERVER;

    /** Folders this side's scripts can load from (besides {@code shared/}). */
    public String folder() {
        return this == CLIENT ? "client" : "server";
    }
}

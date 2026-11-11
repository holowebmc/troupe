package dev.holoweb.troupe.api;

/** The modules of one side. */
public interface Modules {
    Side side();

    /** Creates the module, or returns the existing one with that name. Names: a-z, 0-9, '_' and '-'. */
    Module module(String name);
}

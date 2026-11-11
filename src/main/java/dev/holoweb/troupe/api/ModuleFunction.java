package dev.holoweb.troupe.api;

@FunctionalInterface
public interface ModuleFunction {
    /** A thrown exception becomes a Lua error with its message. Return null for no value. */
    Object call(Args args) throws Exception;
}

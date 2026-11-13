package dev.holoweb.troupe.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncFunction {
    /**
     * Runs on the game thread and returns at once; the script waits, without holding the game, until the stage
     * completes (on any thread). Its value goes back to Lua; failing it raises a Lua error with the message.
     */
    CompletionStage<?> call(Args args) throws Exception;
}

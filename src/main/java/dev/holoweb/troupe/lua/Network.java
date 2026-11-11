package dev.holoweb.troupe.lua;

/**
 * What resources send to the other side; implemented by {@link Link} (one connection) and {@link Links} (the server's
 * players). Values are JSON text. Everything happens on the game thread.
 */
public interface Network {
    @FunctionalInterface
    interface Reply {
        /** {@code json} is the result, or the error message as a JSON string when {@code ok} is false. */
        void reply(boolean ok, String json);
    }

    /** Client: to the server ({@code player} is ignored). Server: to one player, or to all when {@code player} is null. */
    void emit(String resource, String player, String name, String json);

    /** Client: to the server. Server: to one player. Fails (never hangs) if the peer leaves or isn't there. */
    void call(String resource, String player, String name, String json, Reply reply);

    /** Server → clients: a player's state ({@code player} set) or global state; {@code json} null removes the key. */
    void state(String resource, String player, String key, String json);

    /** Server → clients: the resource stopped, its state is gone. */
    void clear(String resource);

    Network NONE = new Network() {
        public void emit(String resource, String player, String name, String json) {}

        public void call(String resource, String player, String name, String json, Reply reply) {
            reply.reply(false, Json.encode("not connected"));
        }

        public void state(String resource, String player, String key, String json) {}

        public void clear(String resource) {}
    };
}

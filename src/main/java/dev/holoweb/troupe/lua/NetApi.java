package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.Protocol;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.function.RegisteredFunction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code net} and {@code state} globals of one resource, and what arrives for it from the other side.
 * <pre>
 * server: net.handle(name, format, fn(player, args))   net.emit(player, name, args)   net.broadcast(name, args)
 *         net.call(player, name, args)                  player.state.k = v              state.global.k = v
 * client: net.handle(name, fn(args))                    net.emit(name, args)            net.call(name, args)
 *         state.player.k   state.global.k (read-only)   state.watch(k, fn)              state.watch_global(k, fn)
 * </pre>
 * Handlers on the server must declare the format of their arguments ({@link Schema}); calls return the handler's
 * first result.
 */
final class NetApi {
    private record Handler(LuaFunction fn, Schema schema) {}

    private final LuaRuntime rt;
    private final boolean server;
    private final Map<String, Handler> handlers = new HashMap<>();
    private final Map<String, List<LuaFunction>> watchers = new HashMap<>();
    private final Map<String, LuaTable> players = new HashMap<>();

    NetApi(LuaRuntime rt, LuaTable globals) throws LuaError {
        this.rt = rt;
        this.server = rt.side == Side.SERVER;

        LuaTable net = RegisteredFunction.bind(new RegisteredFunction[]{
                RegisteredFunction.ofV("handle", (s, a) -> {
                    handle(a);
                    return Constants.NONE;
                }),
                RegisteredFunction.ofV("emit", (s, a) -> {
                    if (server) send(playerId(a.arg(1)), a.arg(2).checkString(), a.arg(3));
                    else send(null, a.arg(1).checkString(), a.arg(2));
                    return Constants.NONE;
                }),
        });
        if (server) net.rawset("broadcast", LibFunction.createV((s, a) -> {
            send(null, a.arg(1).checkString(), a.arg(2));
            return Constants.NONE;
        }));
        globals.rawset("net", net);

        LuaTable state = new LuaTable();
        if (server) {
            state.rawset("global", proxy(null, true));
        } else {
            state.rawset("player", proxy("", false));
            state.rawset("global", proxy(null, false));
            state.rawset("watch", LibFunction.create((s, key, fn) -> watch("p", key, fn)));
            state.rawset("watch_global", LibFunction.create((s, key, fn) -> watch("g", key, fn)));
        }
        globals.rawset("state", state);
    }

    private void handle(Varargs a) throws LuaError {
        String name = a.arg(1).checkString();
        Schema schema = null;
        LuaFunction fn;
        if (a.arg(2) instanceof LuaFunction f && !server) {
            fn = f;
        } else {
            if (!(a.arg(2) instanceof LuaTable)) throw new LuaError(server
                    ? "net.handle on the server needs the format of the arguments: net.handle(name, { field = 'type' }, fn)"
                    : "bad argument #2 to 'handle' (function or format table expected)");
            try {
                schema = Schema.of(Values.toJava(rt, a.arg(2), 0));
            } catch (IllegalArgumentException e) {
                throw new LuaError("net.handle '" + name + "': " + e.getMessage());
            }
            fn = a.arg(3).checkFunction();
        }
        if (handlers.containsKey(name)) throw new LuaError("net.handle: '" + name + "' already has a handler");
        handlers.put(name, new Handler(fn, schema));
    }

    private void send(String player, String name, LuaValue args) throws LuaError {
        rt.host.network().emit(rt.name, player, name, encode(args));
    }

    /** {@code net.call} waiting in a task: client {@code (name, args)}, server {@code (player, name, args)}. */
    void call(Varargs a, LuaRuntime.Done done) throws LuaError {
        String player = server ? playerId(a.arg(1)) : null;
        int i = server ? 2 : 1;
        String name = a.arg(i).checkString();
        rt.host.network().call(rt.name, player, name, encode(a.arg(i + 1)), (ok, json) -> {
            try {
                Object value = Json.decode(json);
                if (ok) done.done(null, Values.toLua(rt, value));
                else done.done(String.valueOf(value), Constants.NONE);
            } catch (IllegalArgumentException | LuaError e) {
                done.done("bad reply to '" + name + "': " + e.getMessage(), Constants.NONE);
            }
        });
    }

    private String encode(LuaValue value) throws LuaError {
        String json;
        try {
            json = Json.encode(Values.toJava(rt, value, 0));
        } catch (IllegalArgumentException e) {
            throw new LuaError(e.getMessage());
        }
        int max = server ? Protocol.MAX_JSON_TO_CLIENT : Protocol.MAX_JSON_TO_SERVER;
        if (json.getBytes(StandardCharsets.UTF_8).length > max) throw new LuaError("too big to send (max " + (max >> 10) + " KiB)");
        return json;
    }

    // ---- from the other side

    void receiveEvent(Peer from, String name, String json) {
        Handler h = handlers.get(name);
        if (h == null) return;
        Varargs args = arguments(h, from, name, json);
        if (args != null) rt.spawn(h.fn(), args, "net " + name, null);
    }

    void receiveCall(Peer from, String name, String json, Network.Reply reply) {
        Handler h = handlers.get(name);
        if (h == null) {
            reply.reply(false, Json.encode("no handler '" + name + "' in " + rt.name));
            return;
        }
        Varargs args = arguments(h, from, name, json);
        if (args == null) {
            reply.reply(false, Json.encode("bad arguments for '" + name + "'"));
            return;
        }
        rt.spawn(h.fn(), args, "net " + name, (err, results) -> {
            if (err != null) {
                // The caller gets the message (error('saldo insuficiente', 0) reads cleanly), not the stack trace.
                if (server) LuaRuntime.LOG.warn("[{}] net {}: {}", rt.name, name, err);
                reply.reply(false, Json.encode(err.lines().findFirst().orElse(err)));
                return;
            }
            try {
                reply.reply(true, encode(results.first()));
            } catch (LuaError e) {
                reply.reply(false, Json.encode("'" + name + "' returned something unsendable: " + Values.readable(e.getMessage())));
            }
        });
    }

    /** Decoded and (on the server) validated handler arguments, or null after logging why not. */
    private Varargs arguments(Handler h, Peer from, String name, String json) {
        try {
            Object args = Json.decode(json);
            if (h.schema() != null) h.schema().validate(args);
            LuaValue lua = Values.toLua(rt, args);
            return server ? ValueFactory.varargsOf(player(from), lua) : ValueFactory.varargsOf(lua);
        } catch (IllegalArgumentException | LuaError e) {
            LuaRuntime.LOG.warn("[{}] rejected '{}' from {}: {}", rt.name, name, from == null ? "the server" : from.name(), e.getMessage());
            return null;
        }
    }

    // ---- players and state

    /**
     * The Lua handle of a player: {@code { id, name, state }} plus the methods in {@code player_methods} (prelude),
     * one per player while they're online. On the client (no state of other players) it's just {@code { id, name }}.
     */
    LuaTable player(Peer peer) {
        return players.computeIfAbsent(peer.id(), id -> {
            LuaTable t = new LuaTable();
            t.rawset("id", Values.lua(id));
            t.rawset("name", Values.lua(peer.name()));
            if (server) {
                t.rawset("state", proxy(id, true));
                LuaTable meta = new LuaTable();
                meta.rawset("__index", rt.playerMethods);
                t.setMetatable(null, meta);
            }
            return t;
        });
    }

    void playerLeft(String id) {
        players.remove(id);
    }

    private static String playerId(LuaValue v) throws LuaError {
        LuaValue id = v instanceof LuaTable t ? t.rawget("id") : v;
        if (!id.isString()) throw new LuaError("player expected (a player table or its id)");
        return Values.str(id);
    }

    /** A table whose fields read (and on the server write) the replicated state of {@code player} (null = global). */
    private LuaTable proxy(String player, boolean writable) {
        LuaTable meta = new LuaTable();
        meta.rawset("__index", LibFunction.create((s, self, key) -> {
            String json = rt.host.getState(rt.name, player, Values.str(key));
            try {
                return json == null ? Constants.NIL : Values.toLua(rt, Json.decode(json));
            } catch (IllegalArgumentException e) {
                return Constants.NIL;
            }
        }));
        meta.rawset("__newindex", LibFunction.create((s, self, key, value) -> {
            if (!writable) throw new LuaError("state is read-only on the client; the server writes it");
            rt.host.setState(rt.name, player, Values.str(key), value.isNil() ? null : encode(value));
            return Constants.NIL;
        }));
        LuaTable t = new LuaTable();
        t.setMetatable(null, meta);
        return t;
    }

    private LuaValue watch(String scope, LuaValue key, LuaValue fn) throws LuaError {
        watchers.computeIfAbsent(scope + ":" + key.checkString(), k -> new ArrayList<>()).add(fn.checkFunction());
        return Constants.NIL;
    }

    /** Client: a state value changed (json null: removed). */
    void stateChanged(boolean global, String key, String json) {
        List<LuaFunction> fns = watchers.get((global ? "g:" : "p:") + key);
        if (fns == null) return;
        LuaValue value;
        try {
            value = json == null ? Constants.NIL : Values.toLua(rt, Json.decode(json));
        } catch (IllegalArgumentException | LuaError e) {
            return;
        }
        for (LuaFunction fn : List.copyOf(fns)) rt.spawn(fn, ValueFactory.varargsOf(value), "state.watch " + key, null);
    }

    void close() {
        handlers.clear();
        watchers.clear();
        players.clear();
    }
}

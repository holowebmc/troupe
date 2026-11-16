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
 *         net.call(player, name, args, {timeout = s})   player.state.k = v              state.global.k = v
 * client: net.handle(name, fn(args))                    net.emit(name, args)            net.call(name, args, {timeout = s})
 *         state.player.k   state.global.k (read-only)   state.watch(k, fn)              state.watch_global(k, fn)
 * entities: server  entity.state.k = v   player.public.k = v     (goes to the players who see that entity or player)
 *           client  state.entity(id).k (read-only)               state.watch_entity(k, fn(id, value))
 * </pre>
 * Handlers on the server must declare the format of their arguments ({@link Schema}); calls return the handler's
 * first result, or fail after {@code timeout} seconds (default {@value #DEFAULT_TIMEOUT}) without one.
 */
final class NetApi {
    private record Handler(LuaFunction fn, Schema schema) {}

    /** When a waiting {@code net.call} gives up, and what it says then. */
    record Timeout(long nanos, String message) {}

    static final double DEFAULT_TIMEOUT = 10;

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
            state.rawset("watch_entity", LibFunction.create((s, key, fn) -> watch("e", key, fn)));
            state.rawset("entity", LibFunction.create((s, id) -> entityState(id.checkString())));
        }
        globals.rawset("state", state);
    }

    /** Editor definitions: one {@code ---@overload} per handler, for the functions the other side calls it with. */
    void stubs(Map<String, List<String>> overloads) {
        new java.util.TreeMap<>(handlers).forEach((name, h) -> {
            String args = h.schema() == null ? "any" : h.schema().luaType();
            if (server) {
                overloads.computeIfAbsent("emit", k -> new java.util.ArrayList<>()).add("fun(name: \"" + name + "\", args: " + args + ")");
                overloads.computeIfAbsent("call", k -> new java.util.ArrayList<>()).add("fun(name: \"" + name + "\", args: " + args + ", opts?: { timeout: number }): any");
            } else {
                overloads.computeIfAbsent("emit", k -> new java.util.ArrayList<>()).add("fun(player: Player|string, name: \"" + name + "\", args: " + args + ")");
                overloads.computeIfAbsent("broadcast", k -> new java.util.ArrayList<>()).add("fun(name: \"" + name + "\", args: " + args + ")");
            }
        });
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
        if (name.startsWith(ResourceHost.RESERVED)) throw new LuaError("names starting with 'troupe:' are Troupe's own");
        if (handlers.containsKey(name)) throw new LuaError("net.handle: '" + name + "' already has a handler");
        handlers.put(name, new Handler(fn, schema));
    }

    private void send(String player, String name, LuaValue args) throws LuaError {
        if (name.startsWith(ResourceHost.RESERVED)) throw new LuaError("names starting with 'troupe:' are Troupe's own");
        rt.host.network().emit(rt.name, player, name, encode(args));
    }

    /**
     * {@code net.call} waiting in a task: client {@code (name, args, opts)}, server {@code (player, name, args, opts)};
     * {@code opts.timeout} in seconds.
     */
    Timeout call(Varargs a, LuaRuntime.Done done) throws LuaError {
        String player = server ? playerId(a.arg(1)) : null;
        int i = server ? 2 : 1;
        String name = a.arg(i).checkString();
        double timeout = DEFAULT_TIMEOUT;
        if (a.arg(i + 2) instanceof LuaTable opts && !opts.rawget("timeout").isNil()) {
            timeout = opts.rawget("timeout").checkDouble();
            if (!(timeout > 0)) throw new LuaError("net.call: timeout must be > 0 seconds");
        } else if (!a.arg(i + 2).isNil()) {
            throw new LuaError("bad argument #" + (i + 2) + " to 'call' (options table expected)");
        }
        rt.host.network().call(rt.name, player, name, encode(a.arg(i + 1)), (ok, json) -> {
            try {
                Object value = Json.decode(json);
                if (ok) done.done(null, Values.toLua(rt, value));
                else done.done(String.valueOf(value), Constants.NONE);
            } catch (IllegalArgumentException | LuaError e) {
                done.done("bad reply to '" + name + "': " + e.getMessage(), Constants.NONE);
            }
        });
        String seconds = timeout == Math.rint(timeout) ? String.valueOf((long) timeout) : String.valueOf(timeout);
        return new Timeout((long) (timeout * 1e9), "net.call '" + name + "' got no reply in " + seconds + " s");
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
                t.rawset("public", entityState(id));
            }
            LuaTable meta = new LuaTable(); // on the client only what mods added (Modules.method)
            meta.rawset("__index", rt.playerMethods);
            t.setMetatable(null, meta);
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
            if (Values.str(key).startsWith(ResourceHost.RESERVED)) throw new LuaError("state keys starting with 'troupe:' are Troupe's own");
            rt.host.setState(rt.name, player, Values.str(key), value.isNil() ? null : encode(value));
            return Constants.NIL;
        }));
        LuaTable t = new LuaTable();
        t.setMetatable(null, meta);
        return t;
    }

    /** A table whose fields read (and on the server write) the state of one entity, as seen by this side. */
    LuaTable entityState(String entity) {
        LuaTable meta = new LuaTable();
        meta.rawset("__index", LibFunction.create((s, self, key) -> {
            String json = rt.host.getEntityState(rt.name, entity, Values.str(key));
            try {
                return json == null ? Constants.NIL : Values.toLua(rt, Json.decode(json));
            } catch (IllegalArgumentException e) {
                return Constants.NIL;
            }
        }));
        meta.rawset("__newindex", LibFunction.create((s, self, key, value) -> {
            if (!server) throw new LuaError("state is read-only on the client; the server writes it");
            if (Values.str(key).startsWith(ResourceHost.RESERVED)) throw new LuaError("state keys starting with 'troupe:' are Troupe's own");
            rt.host.setEntityState(rt.name, entity, Values.str(key), value.isNil() ? null : encode(value));
            return Constants.NIL;
        }));
        LuaTable t = new LuaTable();
        t.setMetatable(null, meta);
        return t;
    }

    /** Client: the state of an entity in sight changed (json null: removed, or the entity left sight). */
    void entityStateChanged(String entity, String key, String json) {
        List<LuaFunction> fns = watchers.get("e:" + key);
        if (fns == null) return;
        LuaValue value;
        try {
            value = json == null ? Constants.NIL : Values.toLua(rt, Json.decode(json));
        } catch (IllegalArgumentException | LuaError e) {
            return;
        }
        for (LuaFunction fn : List.copyOf(fns)) rt.spawn(fn, ValueFactory.varargsOf(Values.lua(entity), value), "state.watch_entity " + key, null);
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

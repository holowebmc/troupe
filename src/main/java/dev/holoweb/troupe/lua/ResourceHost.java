package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.ResourceInfo;
import org.squiddev.cobalt.LuaError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * The resources of one side: lifecycle (start in dependency order, stop dependents first), the timer clock, routing
 * of network messages and exports, and the replicated state. Game thread only.
 */
public final class ResourceHost {
    public record Entry(ResourceInfo info, LuaRuntime.Files files) {}

    private record Running(Entry entry, LuaRuntime runtime) {}

    public static final class ResourceException extends Exception {
        public ResourceException(String message) {
            super(message);
        }
    }

    /** Result of a call into another resource's exports: {@code error} null on success. */
    @FunctionalInterface
    interface ExportDone {
        void done(String error, List<Object> results);
    }

    @FunctionalInterface
    public interface StateVisitor {
        void visit(String resource, String key, String json);
    }

    private final Side side;
    private final Executor executor;
    private final LongSupplier clock;
    private final ModuleRegistry modules;
    private Network network = Network.NONE;
    private UiBackend ui;
    private final List<java.util.function.Consumer<String>> stopListeners = new ArrayList<>();
    private Map<String, Entry> available = Map.of();
    private final LinkedHashMap<String, Running> running = new LinkedHashMap<>();
    // resource → key → json. On the client the player map has a single player, "".
    private final Map<String, Map<String, String>> globalState = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> playerState = new HashMap<>();

    /** {@code executor} runs tasks on the game thread of this side (module events, callbacks). */
    public ResourceHost(Side side, Executor executor) {
        this(side, executor, System::nanoTime);
    }

    /** {@code clock}: nanoseconds, for timers and rate limits. */
    public ResourceHost(Side side, Executor executor, LongSupplier clock) {
        this.side = side;
        this.executor = executor;
        this.clock = clock;
        this.modules = new ModuleRegistry(side, executor, () -> running.values().stream().map(Running::runtime).toList());
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public Side side() {
        return side;
    }

    Executor executor() {
        return executor;
    }

    long now() {
        return clock.getAsLong();
    }

    public Network network() {
        return network;
    }

    public void setNetwork(Network network) {
        this.network = network == null ? Network.NONE : network;
    }

    /** Built-in modules hear when a resource stops, to drop what it registered (commands...). */
    public void onStop(java.util.function.Consumer<String> listener) {
        stopListeners.add(listener);
    }

    UiBackend ui() {
        return ui;
    }

    /** Client: where {@code ui.*} pages go; null (no Vitrine) makes them errors. Set before resources start. */
    public void setUi(UiBackend ui) {
        this.ui = ui;
    }

    /** Replaces what can be started; running resources keep the version they started with. */
    public void setAvailable(Map<String, Entry> entries) {
        available = new TreeMap<>(entries);
    }

    public Set<String> available() {
        return available.keySet();
    }

    public boolean isRunning(String name) {
        return running.containsKey(name);
    }

    // ---- lifecycle

    /** Starts the resource and, first, its dependencies. */
    public void start(String name) throws ResourceException {
        start(name, new ArrayDeque<>());
    }

    private void start(String name, Deque<String> chain) throws ResourceException {
        if (running.containsKey(name)) return;
        Entry entry = available.get(name);
        if (entry == null) throw new ResourceException("unknown resource '" + name + "'");
        if (chain.contains(name)) throw new ResourceException("dependency cycle: " + String.join(" -> ", chain.reversed()) + " -> " + name);
        chain.push(name);
        for (String dep : entry.info().dependencies()) {
            // A dependency with no client files isn't sent to clients; the server already checked it exists.
            if (!available.containsKey(dep) && side == Side.CLIENT) continue;
            try {
                start(dep, chain);
            } catch (ResourceException e) {
                throw new ResourceException(name + " needs " + dep + ": " + e.getMessage());
            }
        }
        chain.pop();
        for (String mod : entry.info().mods(side))
            if (!modules.has(mod)) throw new ResourceException(name + " needs the module '" + mod + "' on the " + side.name().toLowerCase() + " (a mod is missing)");

        LuaRuntime runtime;
        try {
            runtime = new LuaRuntime(name, side, entry.files(), this);
        } catch (LuaError e) {
            throw new ResourceException(name + ": " + Values.readable(e.getMessage()));
        }
        running.put(name, new Running(entry, runtime)); // before main.lua: its handlers may fire right away
        try {
            runtime.start();
        } catch (LuaError e) {
            stop(name);
            throw new ResourceException(Values.readable(e.getMessage()));
        }
        LuaRuntime.LOG.info("Started {} ({})", name, side.name().toLowerCase());
    }

    /** Stops the resource and, first, every running resource that depends on it. Returns what stopped, in order. */
    public List<String> stop(String name) {
        List<String> stopped = new ArrayList<>();
        stop(name, stopped);
        return stopped;
    }

    private void stop(String name, List<String> stopped) {
        if (!running.containsKey(name)) return;
        for (Map.Entry<String, Running> r : List.copyOf(running.entrySet()))
            if (r.getValue().entry().info().dependencies().contains(name)) stop(r.getKey(), stopped);
        Running r = running.remove(name);
        if (r == null) return;
        r.runtime().close();
        for (var l : stopListeners) l.accept(name);
        if (side == Side.SERVER && (globalState.remove(name) != null | playerState.remove(name) != null)) network.clear(name);
        stopped.add(name);
        LuaRuntime.LOG.info("Stopped {} ({})", name, side.name().toLowerCase());
    }

    /** Stops and starts again the resource and the dependents that were running. */
    public void restart(String name) throws ResourceException {
        List<String> stopped = stop(name);
        start(name);
        for (String dependent : stopped.reversed()) start(dependent);
    }

    /** Starts every available resource; one failing doesn't keep the others from starting. */
    public void startAll() {
        for (String name : available.keySet()) {
            try {
                start(name);
            } catch (ResourceException e) {
                LuaRuntime.LOG.error("Couldn't start {}: {}", name, e.getMessage());
            }
        }
    }

    public void stopAll() {
        for (String name : List.copyOf(running.keySet()).reversed()) stop(name);
    }

    /** Every game tick: wakes timers that are due. */
    public void tick() {
        long now = now();
        for (Running r : List.copyOf(running.values())) r.runtime().tick(now);
    }

    private LuaRuntime runtime(String resource) {
        Running r = running.get(resource);
        return r == null ? null : r.runtime();
    }

    void callExport(String resource, String fn, List<Object> args, ExportDone done) {
        LuaRuntime target = runtime(resource);
        if (target == null) done.done("resource '" + resource + "' isn't running", null);
        else target.invokeExport(fn, args, done);
    }

    // ---- network: what arrives from the other side (via Link)

    void receiveEvent(String resource, Peer from, String name, String json) {
        LuaRuntime r = runtime(resource);
        if (r != null) r.net.receiveEvent(from, name, json);
    }

    void receiveCall(String resource, Peer from, String name, String json, Network.Reply reply) {
        LuaRuntime r = runtime(resource);
        if (r == null) reply.reply(false, Json.encode("resource '" + resource + "' isn't running"));
        else r.net.receiveCall(from, name, json, reply);
    }

    /** Client: the server changed a state value. Kept even before the resource starts here. */
    void receiveState(String resource, boolean global, String key, String json) {
        Map<String, String> map = global ? globalState.computeIfAbsent(resource, k -> new HashMap<>())
                : playerState.computeIfAbsent(resource, k -> new HashMap<>()).computeIfAbsent("", k -> new HashMap<>());
        if (json == null) map.remove(key);
        else map.put(key, json);
        LuaRuntime r = runtime(resource);
        if (r != null) r.net.stateChanged(global, key, json);
    }

    /** Client: the resource stopped on the server, so its state is gone. */
    void receiveClear(String resource) {
        Map<String, String> global = globalState.remove(resource);
        Map<String, Map<String, String>> players = playerState.remove(resource);
        LuaRuntime r = runtime(resource);
        if (r == null) return;
        if (global != null) for (String key : global.keySet()) r.net.stateChanged(true, key, null);
        if (players != null && players.containsKey("")) for (String key : players.get("").keySet()) r.net.stateChanged(false, key, null);
    }

    // ---- replicated state

    /** {@code player} null: global. On the client, any non-null player means "me". */
    String getState(String resource, String player, String key) {
        Map<String, String> map = player == null ? globalState.get(resource)
                : playerState.getOrDefault(resource, Map.of()).get(side == Side.CLIENT ? "" : player);
        return map == null ? null : map.get(key);
    }

    /** Server: stores and sends to the player (or everyone, for global state). {@code json} null removes. */
    void setState(String resource, String player, String key, String json) {
        Map<String, String> map = player == null ? globalState.computeIfAbsent(resource, k -> new HashMap<>())
                : playerState.computeIfAbsent(resource, k -> new HashMap<>()).computeIfAbsent(player, k -> new HashMap<>());
        if (json == null) map.remove(key);
        else map.put(key, json);
        network.state(resource, player, key, json);
    }

    public void forEachGlobalState(StateVisitor visitor) {
        globalState.forEach((resource, map) -> map.forEach((key, json) -> visitor.visit(resource, key, json)));
    }

    /** Server: the player left; their state goes with them. */
    public void playerLeft(String playerId) {
        for (Map<String, Map<String, String>> byPlayer : playerState.values()) byPlayer.remove(playerId);
        for (Running r : running.values()) r.runtime().net.playerLeft(playerId);
    }

    /** Client: disconnected; nothing of that server's state stays. */
    public void resetState() {
        globalState.clear();
        playerState.clear();
    }
}

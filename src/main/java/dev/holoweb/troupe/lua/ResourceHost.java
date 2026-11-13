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

    /** Global state as it reaches a client ({@code json} null: removed), for Troupe's own modules. */
    @FunctionalInterface
    public interface StateListener {
        void changed(String resource, String key, String json);
    }

    /** A message from the other side meant for Troupe itself (names start with "troupe:"), not for a script. */
    @FunctionalInterface
    public interface BuiltinListener {
        void received(String resource, Peer from, String name, String json);
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
    private List<String> javaAllow = List.of();
    private LuaRuntime.Limits limits = LuaRuntime.Limits.DEFAULT;
    private final List<java.util.function.BiConsumer<String, String>> faultListeners = new ArrayList<>();
    /** Live heap after the last GC, as a fraction of the maximum. A field so a test can fake it. */
    java.util.function.DoubleSupplier heapPressure = ResourceHost::liveHeap;
    private int ticks;
    private final List<StateListener> stateListeners = new ArrayList<>();
    private final List<BuiltinListener> builtinListeners = new ArrayList<>();
    /**
     * Stopped for going over a limit, with the generation it had: startAll and apply leave it alone until someone
     * starts it by name (server) or the server restarts it (client), or it would go over the limit again right away.
     */
    private final Map<String, Integer> faulted = new HashMap<>();
    private String version;
    private final List<java.util.function.Consumer<String>> stopListeners = new ArrayList<>();
    private final List<java.util.function.Consumer<String>> startListeners = new ArrayList<>();
    private Map<String, Entry> available = Map.of();
    private final LinkedHashMap<String, Running> running = new LinkedHashMap<>();
    /** Server: bumps on every start, so clients can tell a restart. Client: what the server last said. */
    private final Map<String, Integer> generations = new HashMap<>();
    // resource → key → json. On the client the player map has a single player, "".
    private final Map<String, Map<String, String>> globalState = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> playerState = new HashMap<>();
    /** resource → entity → key → json. Server: what scripts set. Client: what the server sent about entities in sight. */
    private final Map<String, Map<String, Map<String, String>>> entityState = new HashMap<>();
    private java.util.function.Function<String, java.util.Collection<String>> entityWatchers = entity -> List.of();

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
        modules.global("json")
                .function("encode", a -> Json.encode(a.get(0)))
                .function("decode", a -> Json.decode(a.string(0)));
        if (side == Side.SERVER) HttpApi.register(modules); // never on the client: it would be the server browsing from the player's PC
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

    /** Built-in modules hear when a resource has started (its main.lua ran up to its first wait). */
    public void onStart(java.util.function.Consumer<String> listener) {
        startListeners.add(listener);
    }

    /** This build's Troupe version, for resources that ask for a minimum ({@code "troupe"} in resource.json). */
    public void setVersion(String version) {
        this.version = version;
    }

    /** Client: Troupe's own modules follow the global state the server sends (their keys start with "troupe:"). */
    public void onState(StateListener listener) {
        stateListeners.add(listener);
    }

    public void onBuiltin(BuiltinListener listener) {
        builtinListeners.add(listener);
    }

    LuaRuntime.Limits limits() {
        return limits;
    }

    /** For resources started from now on. */
    public void setLimits(LuaRuntime.Limits limits) {
        this.limits = limits;
    }

    /** Hears {@code (resource, why)} after a resource was stopped for going over a limit. */
    public void onFault(java.util.function.BiConsumer<String, String> listener) {
        faultListeners.add(listener);
    }

    List<String> javaAllow() {
        return javaAllow;
    }

    /** Server: packages and classes resources may reach through {@code java.*} (empty: off). Before resources start. */
    public void setJavaAllow(List<String> allow) {
        javaAllow = List.copyOf(allow);
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
        faulted.remove(name);
        start(name, new ArrayDeque<>());
    }

    private void start(String name, Deque<String> chain) throws ResourceException {
        if (running.containsKey(name)) return;
        if (faulted.containsKey(name)) throw new ResourceException(name + " was stopped for going over a limit");
        Entry entry = available.get(name);
        if (entry == null) throw new ResourceException("unknown resource '" + name + "'");
        if (chain.contains(name)) throw new ResourceException("dependency cycle: " + String.join(" -> ", reversed(chain)) + " -> " + name);
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
        if (!entry.info().runsOn(version))
            throw new ResourceException(name + " needs Troupe " + entry.info().minTroupe() + " or later on the " + side.name().toLowerCase() + " (this is " + version + ")");
        for (String mod : entry.info().mods(side))
            if (!modules.has(mod)) throw new ResourceException(name + " needs the module '" + mod + "' on the " + side.name().toLowerCase() + " (a mod is missing)");

        LuaRuntime runtime;
        try {
            runtime = new LuaRuntime(name, side, entry.files(), this);
        } catch (LuaError e) {
            throw new ResourceException(name + ": " + Values.readable(e.getMessage()));
        }
        running.put(name, new Running(entry, runtime)); // before main.lua: its handlers may fire right away
        if (side == Side.SERVER) generations.merge(name, 1, Integer::sum);
        try {
            runtime.start();
        } catch (LuaError e) {
            stop(name);
            throw new ResourceException(Values.readable(e.getMessage()));
        }
        LuaRuntime.LOG.info("Started {} ({})", name, side.name().toLowerCase());
        for (var l : startListeners) l.accept(name);
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
        if (side == Side.SERVER && (globalState.remove(name) != null | playerState.remove(name) != null | entityState.remove(name) != null)) network.clear(name);
        stopped.add(name);
        LuaRuntime.LOG.info("Stopped {} ({})", name, side.name().toLowerCase());
    }

    /** Stops and starts again the resource and the dependents that were running. */
    public void restart(String name) throws ResourceException {
        List<String> stopped = stop(name);
        start(name);
        for (String dependent : reversed(stopped)) start(dependent);
    }

    /** Starts every available resource; one failing doesn't keep the others from starting. */
    public void startAll() {
        for (String name : available.keySet()) {
            if (faulted.containsKey(name)) continue;
            try {
                start(name);
            } catch (ResourceException e) {
                LuaRuntime.LOG.error("Couldn't start {}: {}", name, e.getMessage());
            }
        }
    }

    public void stopAll() {
        for (String name : reversed(running.keySet())) stop(name);
    }

    /** Server: running resources and their generation (what clients are told to run). */
    public Map<String, Integer> running() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String name : running.keySet()) out.put(name, generations.getOrDefault(name, 0));
        return out;
    }

    /**
     * Client: the server's running resources changed. Stops what's gone and what restarted there (a new generation),
     * with their dependents, then starts everything that isn't running.
     */
    public void apply(Map<String, Entry> entries, Map<String, Integer> serverGenerations) {
        for (String name : reversed(running.keySet()))
            if (!entries.containsKey(name) || !serverGenerations.getOrDefault(name, 0).equals(generations.getOrDefault(name, 0))) stop(name);
        generations.clear();
        generations.putAll(serverGenerations);
        faulted.entrySet().removeIf(e -> !e.getValue().equals(serverGenerations.get(e.getKey())));
        setAvailable(entries);
        startAll();
    }

    /** Server: every scanned resource, its files read from disk (require only builds server/... and shared/... paths). */
    public static Map<String, Entry> entries(dev.holoweb.troupe.net.ServerResources resources) {
        Map<String, Entry> entries = new TreeMap<>();
        for (ResourceInfo info : resources.infos().values()) {
            java.nio.file.Path dir = resources.root().resolve(info.name());
            entries.put(info.name(), new Entry(info, path -> {
                java.nio.file.Path file = dir.resolve(path);
                return java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.readAllBytes(file) : null;
            }));
        }
        return entries;
    }

    /** Client: the server's resources, read from the cache; only paths in the manifest exist. */
    public static Map<String, Entry> entries(dev.holoweb.troupe.net.Protocol.Manifest m, dev.holoweb.troupe.net.BlobCache cache) {
        Map<String, Entry> entries = new TreeMap<>();
        m.resources().forEach((name, files) -> {
            LuaRuntime.Files read = path -> {
                Integer blob = files.get(path);
                return blob == null ? null : cache.read(m.blobs().get(blob));
            };
            try {
                byte[] json = read.read(dev.holoweb.troupe.net.Protocol.RESOURCE_JSON);
                if (json == null) throw new java.io.IOException("no resource.json");
                entries.put(name, new Entry(ResourceInfo.parse(name, new String(json, java.nio.charset.StandardCharsets.UTF_8)), read));
            } catch (java.io.IOException e) {
                LuaRuntime.LOG.error("Resource {}: {}", name, e.getMessage());
            }
        });
        return entries;
    }

    /** A reversed copy (Java 17 has no List.reversed). */
    private static <T> List<T> reversed(java.util.Collection<T> items) {
        List<T> copy = new ArrayList<>(items);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Every game tick: wakes timers that are due, then stops the resources that went over a limit. */
    public void tick() {
        long now = now();
        for (Running r : List.copyOf(running.values())) r.runtime().tick(now);
        if (side == Side.CLIENT && ++ticks % 20 == 0) heapGuard();
        for (Running r : List.copyOf(running.values())) {
            String why = r.runtime().fault();
            String name = r.runtime().name();
            if (why == null || !running.containsKey(name)) continue;
            LuaRuntime.LOG.error("Stopping {} ({}): it {}", name, side.name().toLowerCase(), why);
            faulted.put(name, generations.getOrDefault(name, 0));
            stop(name);
            for (var l : faultListeners) l.accept(name, why);
        }
    }

    /**
     * Client, once a second: retained memory is the one thing the per-step limits can't see (Cobalt has no heap
     * accounting), so when the game's heap is nearly full after a GC, the server's script that allocated the most goes.
     */
    // ponytail: blames by bytes allocated, not retained; walk the Lua heap per resource if this picks wrong in practice
    private void heapGuard() {
        if (heapPressure.getAsDouble() < 0.85) return;
        running.values().stream().map(Running::runtime)
                .filter(r -> r.totalAlloc() >= 64L << 20)
                .max(java.util.Comparator.comparingLong(LuaRuntime::totalAlloc))
                .ifPresent(r -> r.fault("was the script using the most memory when the game ran out of it"));
    }

    /** Names scripts can't use for handlers, events or state keys: they are Troupe's own. */
    public static final String RESERVED = "troupe:";

    private static double liveHeap() {
        long live = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            var usage = pool.getType() == java.lang.management.MemoryType.HEAP ? pool.getCollectionUsage() : null;
            if (usage != null) live += usage.getUsed();
        }
        return (double) live / Runtime.getRuntime().maxMemory();
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
        if (name.startsWith(RESERVED)) {
            for (BuiltinListener l : builtinListeners) l.received(resource, from, name, json);
            return;
        }
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
        if (global) for (StateListener l : stateListeners) l.changed(resource, key, json);
        LuaRuntime r = runtime(resource);
        if (r != null) r.net.stateChanged(global, key, json);
    }

    /** Client: the resource stopped on the server, so its state is gone. */
    void receiveClear(String resource) {
        Map<String, String> global = globalState.remove(resource);
        Map<String, Map<String, String>> players = playerState.remove(resource);
        if (global != null) for (String key : global.keySet()) for (StateListener l : stateListeners) l.changed(resource, key, null);
        Map<String, Map<String, String>> entities = entityState.remove(resource);
        LuaRuntime r = runtime(resource);
        if (r == null) return;
        if (entities != null) entities.forEach((entity, map) -> map.keySet().forEach(key -> r.net.entityStateChanged(entity, key, null)));
        if (global != null) for (String key : global.keySet()) r.net.stateChanged(true, key, null);
        if (players != null && players.containsKey("")) for (String key : players.get("").keySet()) r.net.stateChanged(false, key, null);
    }

    // ---- state of entities, replicated to who can see them

    /** Server: who an entity's state goes to: the players tracking it, and itself when it is a player. */
    public void setEntityWatchers(java.util.function.Function<String, java.util.Collection<String>> watchers) {
        entityWatchers = watchers;
    }

    String getEntityState(String resource, String entity, String key) {
        Map<String, String> map = entityState.getOrDefault(resource, Map.of()).get(entity);
        return map == null ? null : map.get(key);
    }

    /** Server: stores and sends to who sees the entity. {@code json} null removes. */
    void setEntityState(String resource, String entity, String key, String json) {
        Map<String, Map<String, String>> byEntity = entityState.computeIfAbsent(resource, k -> new HashMap<>());
        if (json == null) {
            Map<String, String> map = byEntity.get(entity);
            if (map == null || map.remove(key) == null) return;
            if (map.isEmpty()) byEntity.remove(entity);
        } else {
            byEntity.computeIfAbsent(entity, k -> new HashMap<>()).put(key, json);
        }
        for (String player : entityWatchers.apply(entity)) network.entityState(resource, player, entity, key, json);
    }

    /** Server: {@code player} started seeing {@code entity}. */
    public void showEntity(String entity, String player) {
        entityState.forEach((resource, byEntity) -> {
            Map<String, String> map = byEntity.get(entity);
            if (map != null) map.forEach((key, json) -> network.entityState(resource, player, entity, key, json));
        });
    }

    /** Server: {@code player} just got their link; send what they already see. */
    void showEntities(String player) {
        java.util.Set<String> entities = new java.util.HashSet<>();
        for (Map<String, Map<String, String>> byEntity : entityState.values()) entities.addAll(byEntity.keySet());
        for (String entity : entities) if (entityWatchers.apply(entity).contains(player)) showEntity(entity, player);
    }

    /** Server: {@code player} stopped seeing {@code entity}. */
    public void hideEntity(String entity, String player) {
        entityState.forEach((resource, byEntity) -> {
            if (byEntity.containsKey(entity)) network.entityState(resource, player, entity, null, null);
        });
    }

    /** Server: the entity is gone for good (died, was removed, or was a player who left). */
    public void entityGone(String entity) {
        for (Map<String, Map<String, String>> byEntity : entityState.values()) byEntity.remove(entity);
    }

    /** Client: the server's word about an entity in sight ({@code key} null: out of sight, forget it). */
    void receiveEntityState(String resource, String entity, String key, String json) {
        Map<String, Map<String, String>> byEntity = entityState.computeIfAbsent(resource, k -> new HashMap<>());
        LuaRuntime r = runtime(resource);
        if (key == null) {
            Map<String, String> gone = byEntity.remove(entity);
            if (gone != null && r != null) for (String k : gone.keySet()) r.net.entityStateChanged(entity, k, null);
            return;
        }
        if (json == null) byEntity.getOrDefault(entity, new HashMap<>()).remove(key);
        else byEntity.computeIfAbsent(entity, k -> new HashMap<>()).put(key, json);
        if (r != null) r.net.entityStateChanged(entity, key, json);
    }

    // ---- replicated state

    /** {@code player} null: global. On the client, any non-null player means "me". */
    String getState(String resource, String player, String key) {
        Map<String, String> map = player == null ? globalState.get(resource)
                : playerState.getOrDefault(resource, Map.of()).get(side == Side.CLIENT ? "" : player);
        return map == null ? null : map.get(key);
    }

    /** Server: stores and sends to the player (or everyone, for global state). {@code json} null removes. */
    public void setState(String resource, String player, String key, String json) {
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
        entityGone(playerId);
        for (Running r : running.values()) r.runtime().net.playerLeft(playerId);
    }

    /** Client: disconnected; nothing of that server's state stays. */
    public void resetState() {
        globalState.clear();
        playerState.clear();
        entityState.clear();
        generations.clear();
        faulted.clear();
    }
}

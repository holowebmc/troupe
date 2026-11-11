package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.ResourceInfo;
import org.squiddev.cobalt.LuaError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/** Lifecycle of the resources of one side: start in dependency order, stop dependents first. Game thread only. */
public final class ResourceHost {
    public record Entry(ResourceInfo info, LuaRuntime.Files files) {}

    private record Running(Entry entry, LuaRuntime runtime) {}

    public static final class ResourceException extends Exception {
        public ResourceException(String message) {
            super(message);
        }
    }

    private final Side side;
    private final Executor executor;
    private final ModuleRegistry modules;
    private Map<String, Entry> available = Map.of();
    private final LinkedHashMap<String, Running> running = new LinkedHashMap<>();

    /** {@code executor} runs tasks on the game thread of this side (module events, callbacks). */
    public ResourceHost(Side side, Executor executor) {
        this.side = side;
        this.executor = executor;
        this.modules = new ModuleRegistry(side, executor, () -> running.values().stream().map(Running::runtime).toList());
    }

    public ModuleRegistry modules() {
        return modules;
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
            runtime = new LuaRuntime(name, side, entry.files(), modules, executor);
        } catch (LuaError e) {
            throw new ResourceException(name + ": " + Values.readable(e.getMessage()));
        }
        running.put(name, new Running(entry, runtime)); // before main.lua: its handlers may fire right away
        try {
            runtime.start();
        } catch (LuaError e) {
            running.remove(name);
            runtime.close();
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
}

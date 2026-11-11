package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.api.ModuleFunction;
import dev.holoweb.troupe.api.Modules;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.Protocol;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** The modules of one side; mods fill it through their {@code troupe*} entrypoints. */
public final class ModuleRegistry implements Modules {
    private final Side side;
    private final Executor executor;
    private final Supplier<Collection<LuaRuntime>> running;
    private final Map<String, ModuleImpl> modules = new ConcurrentHashMap<>();

    ModuleRegistry(Side side, Executor executor, Supplier<Collection<LuaRuntime>> running) {
        this.side = side;
        this.executor = executor;
        this.running = running;
    }

    @Override
    public Side side() {
        return side;
    }

    @Override
    public Module module(String name) {
        if (!Protocol.validName(name)) throw new IllegalArgumentException("bad module name '" + name + "': use a-z, 0-9, '_' and '-'");
        return modules.computeIfAbsent(name, ModuleImpl::new);
    }

    ModuleImpl find(String name) {
        return modules.get(name);
    }

    public boolean has(String name) {
        return modules.containsKey(name);
    }

    final class ModuleImpl implements Module {
        private final String name;
        private final Map<String, ModuleFunction> functions = new ConcurrentHashMap<>();
        private final Set<String> events = ConcurrentHashMap.newKeySet();

        ModuleImpl(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Module function(String fn, ModuleFunction function) {
            functions.put(fn, function);
            return this;
        }

        @Override
        public Module event(String event) {
            events.add(event);
            return this;
        }

        @Override
        public void emit(String event, Object... args) {
            if (!events.contains(event)) throw new IllegalArgumentException("module '" + name + "' has no event '" + event + "'");
            Object[] copy = args.clone();
            executor.execute(() -> {
                for (LuaRuntime r : running.get()) r.dispatch(name, event, copy);
            });
        }

        ModuleFunction function(String fn) {
            return functions.get(fn);
        }

        Set<String> functionNames() {
            return functions.keySet();
        }

        boolean hasEvent(String event) {
            return events.contains(event);
        }
    }
}

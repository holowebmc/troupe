package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Callback;
import dev.holoweb.troupe.api.ModuleFunction;
import dev.holoweb.troupe.api.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.compiler.CompileException;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;
import org.squiddev.cobalt.function.RegisteredFunction;
import org.squiddev.cobalt.interrupt.InterruptAction;
import org.squiddev.cobalt.lib.CoreLibraries;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The Lua state of one resource on one side. Single-threaded: everything runs on the side's game thread.
 * <p>
 * Sandbox: Cobalt's standard libraries only (no io, os, debug or Java access); binary chunks can't load because the
 * state has no bytecode format; {@code require} only reaches this resource's {@code shared/} and own-side folder.
 */
public final class LuaRuntime {
    /** A call that runs this long without returning is stopped (it can't be caught with pcall). */
    public static final long TIME_LIMIT_MS = 2000;
    static final Logger LOG = LoggerFactory.getLogger("troupe");

    /** The resource's files by path ({@code shared/config.lua}); null when missing. */
    @FunctionalInterface
    public interface Files {
        byte[] read(String path) throws IOException;
    }

    private static final Pattern MODULE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private static final Set<LuaRuntime> ACTIVE = ConcurrentHashMap.newKeySet();

    static {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Troupe watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            for (LuaRuntime r : ACTIVE) {
                long deadline = r.deadline;
                if (deadline != 0 && now - deadline > 0) r.state.interrupt();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private final String name;
    private final Side side;
    private final Files files;
    private final ModuleRegistry modules;
    private final Executor executor;
    private final LuaState state;

    private volatile long deadline; // System.nanoTime() limit of the running call, 0 when idle
    private boolean timedOut;
    private boolean busy;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private volatile boolean closed;
    private final List<LuaFunction> stopHooks = new ArrayList<>();
    private final Map<String, List<LuaFunction>> listeners = new HashMap<>();
    private final Map<String, LuaTable> moduleTables = new HashMap<>();

    public LuaRuntime(String name, Side side, Files files, ModuleRegistry modules, Executor executor) throws LuaError {
        this.name = name;
        this.side = side;
        this.files = files;
        this.modules = modules;
        this.executor = executor;
        state = LuaState.builder().interruptHandler(this::interrupted).build();
        ACTIVE.add(this);

        CoreLibraries.standardGlobals(state);
        LuaTable g = state.globals();
        RegisteredFunction.bind(g, new RegisteredFunction[]{
                RegisteredFunction.ofV("print", this::print),
                RegisteredFunction.of("on_stop", (s, fn) -> {
                    stopHooks.add(fn.checkFunction());
                    return Constants.NIL;
                }),
        });
        g.rawset("mods", RegisteredFunction.bind(new RegisteredFunction[]{
                RegisteredFunction.of("has", (s, mod) -> ValueFactory.valueOf(modules.find(mod.checkString()) != null)),
                RegisteredFunction.ofV("call", (s, a) -> callModule(a.arg(1).checkString(), a.arg(2).checkString(), a, 3)),
                RegisteredFunction.of("get", (s, mod) -> moduleTable(mod.checkString())),
                RegisteredFunction.of("on", (s, mod, event, fn) -> {
                    listen(mod.checkString(), event.checkString(), fn.checkFunction());
                    return Constants.NIL;
                }),
        }));

        try (InputStream prelude = LuaRuntime.class.getResourceAsStream("/troupe/prelude.lua")) {
            run(LoadState.load(state, prelude, "=troupe", g), ValueFactory.varargsOf(LibFunction.createV(this::loadModule)));
        } catch (IOException | CompileException e) {
            throw new IllegalStateException("broken Troupe prelude", e);
        }
    }

    public String name() {
        return name;
    }

    /** Runs {@code <side>/main.lua}, if there is one. */
    public void start() throws LuaError {
        String path = side.folder() + "/main.lua";
        Varargs chunk = compile(path);
        if (chunk == null) return;
        if (chunk.first().isNil()) throw new LuaError(chunk.arg(2));
        run((LuaFunction) chunk.first(), Constants.NONE);
    }

    /** Runs the {@code on_stop} handlers, then drops every listener. Nothing of this resource runs afterwards. */
    public void close() {
        if (closed) return;
        for (LuaFunction hook : stopHooks) call(hook, Constants.NONE, "on_stop");
        closed = true;
        listeners.clear();
        pending.clear();
        ACTIVE.remove(this);
    }

    /** Calls a Lua function from the host; errors are logged, not thrown. Calls made during a call wait for it. */
    void call(LuaFunction fn, Varargs args, String what) {
        if (closed) return;
        if (busy) {
            pending.add(() -> call(fn, args, what));
            return;
        }
        busy = true;
        try {
            run(fn, args);
        } catch (LuaError e) {
            LOG.error("[{}] {}: {}", name, what, Values.readable(e.getMessage()));
        } finally {
            busy = false;
        }
        while (!busy && !pending.isEmpty()) pending.poll().run();
    }

    void dispatch(String module, String event, Object[] args) {
        List<LuaFunction> fns = listeners.get(module + "\0" + event);
        if (fns == null) return;
        Varargs luaArgs;
        try {
            luaArgs = toLuaArgs(args);
        } catch (LuaError e) {
            LOG.error("[{}] event {}.{}: {}", name, module, event, Values.readable(e.getMessage()));
            return;
        }
        for (LuaFunction fn : List.copyOf(fns)) call(fn, luaArgs, "event " + module + "." + event);
    }

    Callback callback(LuaFunction fn) {
        return new LuaCallback(this, fn);
    }

    record LuaCallback(LuaRuntime runtime, LuaFunction function) implements Callback {
        @Override
        public void invoke(Object... args) {
            runtime.executor.execute(() -> {
                if (runtime.closed) return;
                try {
                    runtime.call(function, runtime.toLuaArgs(args), "callback");
                } catch (LuaError e) {
                    LOG.error("[{}] callback: {}", runtime.name, Values.readable(e.getMessage()));
                }
            });
        }
    }

    private Varargs run(LuaFunction fn, Varargs args) throws LuaError {
        LuaThread thread = new LuaThread(state, fn);
        timedOut = false;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIME_LIMIT_MS);
        try {
            Varargs result = LuaThread.run(thread, args);
            if (timedOut) throw new LuaError("stopped after running " + TIME_LIMIT_MS + " ms without returning");
            // ponytail: no scheduler yet; timer.wait / net.call (phase 3) will resume these.
            if (thread.isAlive()) throw new LuaError("yield outside a coroutine isn't supported yet");
            return result;
        } finally {
            deadline = 0;
        }
    }

    private InterruptAction interrupted() {
        long d = deadline;
        if (d == 0 || System.nanoTime() - d < 0) return InterruptAction.CONTINUE;
        timedOut = true;
        return InterruptAction.SUSPEND; // unwinds the whole call: pcall can't keep a runaway script alive
    }

    private Varargs print(LuaState s, Varargs args) {
        StringBuilder line = new StringBuilder();
        for (int i = 1; i <= args.count(); i++) {
            if (i > 1) line.append('\t');
            line.append(Values.str(args.arg(i)));
        }
        LOG.info("[{}] {}", name, line);
        return Constants.NONE;
    }

    /** {@code require} backend: returns the compiled chunk, or nil and a message. */
    private Varargs loadModule(LuaState s, Varargs args) throws LuaError {
        String module = args.arg(1).checkString();
        String folder = module.substring(0, Math.max(0, module.indexOf('.')));
        if (!MODULE_NAME.matcher(module).matches() || !(folder.equals("shared") || folder.equals(side.folder())))
            return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf("module '" + module + "' not found (modules are shared.x or " + side.folder() + ".x)"));
        Varargs chunk = compile(module.replace('.', '/') + ".lua");
        return chunk != null ? chunk : ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf("module '" + module + "' not found"));
    }

    /** Null when the file doesn't exist; (nil, message) when it doesn't compile. */
    private Varargs compile(String path) throws LuaError {
        byte[] source;
        try {
            source = files.read(path);
        } catch (IOException e) {
            throw new LuaError("can't read " + path + ": " + e.getMessage());
        }
        if (source == null) return null;
        try {
            return LoadState.load(state, new ByteArrayInputStream(source), "@" + name + "/" + path, state.globals());
        } catch (CompileException e) {
            return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf(e.getMessage()));
        }
    }

    private ModuleRegistry.ModuleImpl module(String mod) throws LuaError {
        ModuleRegistry.ModuleImpl m = modules.find(mod);
        if (m == null) throw new LuaError("no module '" + mod + "' on the " + side.name().toLowerCase());
        return m;
    }

    private Varargs callModule(String mod, String fn, Varargs args, int from) throws LuaError {
        ModuleFunction function = module(mod).function(fn);
        if (function == null) throw new LuaError("module '" + mod + "' has no function '" + fn + "'");
        Object result;
        try {
            result = function.call(new Args(name, side, Values.toJava(this, args, from)));
        } catch (LuaError e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new LuaError(mod + "." + fn + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("[{}] {}.{} threw", name, mod, fn, e);
            throw new LuaError(mod + "." + fn + ": " + e);
        }
        return Values.toLua(this, result);
    }

    private LuaValue moduleTable(String mod) throws LuaError {
        LuaTable table = moduleTables.get(mod);
        if (table != null) return table;
        table = new LuaTable();
        for (String fn : module(mod).functionNames())
            table.rawset(fn, LibFunction.createV((s, a) -> callModule(mod, fn, a, 1)));
        moduleTables.put(mod, table);
        return table;
    }

    private void listen(String mod, String event, LuaFunction fn) throws LuaError {
        if (!module(mod).hasEvent(event)) throw new LuaError("module '" + mod + "' has no event '" + event + "'");
        listeners.computeIfAbsent(mod + "\0" + event, k -> new ArrayList<>()).add(fn);
    }

    private Varargs toLuaArgs(Object[] args) throws LuaError {
        LuaValue[] values = new LuaValue[args.length];
        for (int i = 0; i < args.length; i++) values[i] = Values.toLua(this, args[i]);
        return ValueFactory.varargsOf(values);
    }
}

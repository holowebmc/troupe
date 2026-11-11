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
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The Lua state of one resource on one side. Single-threaded: everything runs on the side's game thread.
 * <p>
 * Sandbox: Cobalt's standard libraries only (no io, os, debug or Java access); binary chunks can't load because the
 * state has no bytecode format; {@code require} only reaches this resource's {@code shared/} and own-side folder.
 * <p>
 * Scheduling: every entry from the host (main.lua, handlers, events, callbacks, {@code async}) is a <em>task</em> on
 * its own Lua thread. A task that waits ({@code timer.wait}, {@code net.call}, an export) yields to the host with a
 * private token and is resumed later. Tasks run one after another, never nested.
 */
public final class LuaRuntime {
    /** A task step that runs this long without yielding or returning is stopped (pcall can't catch it). */
    public static final long TIME_LIMIT_MS = 2000;
    static final Logger LOG = LoggerFactory.getLogger("troupe");

    /** The resource's files by path ({@code shared/config.lua}); null when missing. */
    @FunctionalInterface
    public interface Files {
        byte[] read(String path) throws IOException;
    }

    /** How a task ended: {@code error} null on success, else a readable message. */
    @FunctionalInterface
    interface Done {
        void done(String error, Varargs results);
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

    private static final class Task {
        final LuaThread thread;
        final String what;
        final Done done;
        int wake; // bumps on every suspension, so a late answer for an earlier one is ignored

        Task(LuaThread thread, String what, Done done) {
            this.thread = thread;
            this.what = what;
            this.done = done;
        }
    }

    private record Step(Task task, Varargs args) {}

    private record Sleep(long due, Task task, int wake) {}

    final String name;
    final Side side;
    final ResourceHost host;
    private final Files files;
    private final LuaState state;
    private final LuaTable token = new LuaTable();
    /** Methods of player handles on the server; the prelude fills it ({@code player:message(...)}). */
    final LuaTable playerMethods = new LuaTable();
    final NetApi net;
    private final UiApi ui; // client only

    private volatile long deadline; // System.nanoTime() limit of the running step, 0 when idle
    private boolean timedOut;
    private Task current;
    private boolean draining;
    private final ArrayDeque<Step> queue = new ArrayDeque<>();
    private final Map<LuaThread, Task> waiting = new HashMap<>();
    private final PriorityQueue<Sleep> sleeping = new PriorityQueue<>((a, b) -> Long.compare(a.due, b.due));
    private boolean closed;
    private boolean started;
    private LuaValue exports = Constants.NIL;
    private final List<LuaFunction> stopHooks = new ArrayList<>();
    private final Map<String, List<LuaFunction>> listeners = new HashMap<>();
    private final Map<String, LuaTable> moduleTables = new HashMap<>();

    LuaRuntime(String name, Side side, Files files, ResourceHost host) throws LuaError {
        this.name = name;
        this.side = side;
        this.files = files;
        this.host = host;
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
                RegisteredFunction.of("has", (s, mod) -> ValueFactory.valueOf(host.modules().find(mod.checkString()) != null)),
                RegisteredFunction.ofV("call", (s, a) -> callModule(a.arg(1).checkString(), a.arg(2).checkString(), a, 3)),
                RegisteredFunction.of("get", (s, mod) -> moduleTable(mod.checkString())),
                RegisteredFunction.of("on", (s, mod, event, fn) -> {
                    listen(mod.checkString(), event.checkString(), fn.checkFunction());
                    return Constants.NIL;
                }),
        }));
        net = new NetApi(this, g);
        for (ModuleRegistry.ModuleImpl m : host.modules().globals()) {
            LuaTable table = (LuaTable) moduleTable(m.name());
            for (String event : m.eventNames()) {
                String mod = m.name();
                table.rawset("on_" + event, LibFunction.create((s, fn) -> {
                    listen(mod, event, fn.checkFunction());
                    return Constants.NIL;
                }));
            }
            g.rawset(m.name(), table);
        }
        ui = side == Side.CLIENT ? new UiApi(this, g, host.ui(), files) : null;
        if (side == Side.SERVER) new JavaApi(this, g, host.javaAllow()); // never on the client

        LuaTable hostApi = RegisteredFunction.bind(new RegisteredFunction[]{
                RegisteredFunction.of("in_task", s -> ValueFactory.valueOf(current != null && state.getCurrentThread() == current.thread)),
                RegisteredFunction.ofV("spawn", (s, a) -> {
                    spawn(a.arg(1).checkFunction(), a.subargs(2), "async", null);
                    return Constants.NONE;
                }),
        });
        hostApi.rawset("token", token);
        hostApi.rawset("player_methods", playerMethods);
        String[] failure = new String[1];
        try (InputStream prelude = LuaRuntime.class.getResourceAsStream("/troupe/prelude.lua")) {
            LuaFunction chunk = LoadState.load(state, prelude, "=troupe", g);
            spawn(chunk, ValueFactory.varargsOf(LibFunction.createV(this::loadModule), hostApi), "prelude", (err, r) -> failure[0] = err);
        } catch (IOException | CompileException e) {
            throw new IllegalStateException("broken Troupe prelude", e);
        }
        if (failure[0] != null) throw new IllegalStateException("broken Troupe prelude: " + failure[0]);
    }

    public String name() {
        return name;
    }

    /**
     * Runs {@code <side>/main.lua}, if there is one. Throws if it fails before its first wait; what it returns is the
     * resource's exports.
     */
    void start() throws LuaError {
        String path = side.folder() + "/main.lua";
        Varargs chunk = compile(path);
        if (chunk != null) {
            if (chunk.first().isNil()) throw new LuaError(chunk.arg(2));
            String[] failure = new String[1];
            spawn((LuaFunction) chunk.first(), Constants.NONE, path, (err, results) -> {
                if (err == null) exports = results.first();
                else if (started) LOG.error("[{}] {}: {}", name, path, err);
                else failure[0] = err;
            });
            if (failure[0] != null) throw new LuaError(failure[0]);
        }
        started = true;
    }

    /** Runs the {@code on_stop} handlers, then fails whatever waits on this resource and drops everything. */
    void close() {
        if (closed) return;
        for (LuaFunction hook : stopHooks) spawn(hook, Constants.NONE, "on_stop", null);
        closed = true;
        List<Task> dropped = new ArrayList<>(waiting.values());
        for (Step s : queue) dropped.add(s.task());
        waiting.clear();
        queue.clear();
        sleeping.clear();
        for (Task t : dropped) drop(t);
        listeners.clear();
        net.close();
        if (ui != null) ui.close();
        ACTIVE.remove(this);
    }

    // ---- tasks

    /** Runs {@code fn} as a new task, now or right after the current one yields or ends. */
    void spawn(LuaFunction fn, Varargs args, String what, Done done) {
        submit(new Task(new LuaThread(state, fn), what, done), args);
    }

    private void submit(Task task, Varargs args) {
        queue.add(new Step(task, args));
        if (draining) return; // the loop below, further up the stack, picks it up
        draining = true;
        try {
            Step s;
            while ((s = queue.poll()) != null) {
                if (closed) drop(s.task());
                else step(s.task(), s.args());
            }
        } finally {
            draining = false;
        }
    }

    private void step(Task task, Varargs args) {
        current = task;
        timedOut = false;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIME_LIMIT_MS);
        Varargs result = Constants.NONE;
        String error = null;
        try {
            result = LuaThread.run(task.thread, args);
            if (timedOut) error = "stopped after running " + TIME_LIMIT_MS + " ms without waiting or returning";
        } catch (LuaError e) {
            error = Values.readable(e.getMessage());
        } finally {
            deadline = 0;
            current = null;
        }
        if (error == null && task.thread.isAlive()) {
            if (result.first() == token) {
                suspend(task, result);
                return;
            }
            error = "coroutine.yield outside a coroutine (use async and timer.wait)";
        }
        finish(task, error, result);
    }

    /** The resource stopped under the task: whoever waits for its result hears so; nothing is logged. */
    private void drop(Task task) {
        if (task.done != null) task.done.done("resource '" + name + "' stopped", Constants.NONE);
    }

    private void finish(Task task, String error, Varargs results) {
        if (task.done != null) task.done.done(error, results);
        else if (error != null) LOG.error("[{}] {}: {}", name, task.what, error);
    }

    /** The task yielded {@code (token, kind, ...)}: start what it waits for. */
    private void suspend(Task task, Varargs request) {
        int wake = ++task.wake;
        waiting.put(task.thread, task);
        String kind = Values.str(request.arg(2));
        Varargs rest = request.subargs(3);
        try {
            switch (kind) {
                case "wait" -> {
                    double ms = rest.arg(1).checkDouble();
                    if (!(ms >= 0)) throw new LuaError("timer.wait: milliseconds must be >= 0");
                    sleeping.add(new Sleep(host.now() + (long) (ms * 1e6), task, wake));
                }
                case "call" -> net.call(rest, (err, results) -> wake(task, wake, err, results));
                case "export" -> host.callExport(Values.str(rest.arg(1)), Values.str(rest.arg(2)), Values.toJava(this, rest, 3),
                        (err, values) -> {
                            if (err != null) {
                                wake(task, wake, err, Constants.NONE);
                                return;
                            }
                            try {
                                wake(task, wake, null, toLuaArgs(values.toArray()));
                            } catch (LuaError e) {
                                wake(task, wake, Values.readable(e.getMessage()), Constants.NONE);
                            }
                        });
                default -> throw new LuaError("unknown wait '" + kind + "'");
            }
        } catch (LuaError e) {
            wake(task, wake, Values.readable(e.getMessage()), Constants.NONE);
        }
    }

    /** The answer for a waiting task: resumes it with {@code (true, results...)} or {@code (false, error)}. */
    void wake(Task task, int wake, String error, Varargs results) {
        if (closed || task.wake != wake || waiting.remove(task.thread) == null) return;
        submit(task, error == null
                ? ValueFactory.varargsOf(Constants.TRUE, results)
                : ValueFactory.varargsOf(Constants.FALSE, Values.lua(error)));
    }

    /** Wakes sleepers that are due. Called every game tick. */
    void tick(long now) {
        Sleep s;
        while ((s = sleeping.peek()) != null && now - s.due() >= 0) {
            sleeping.poll();
            wake(s.task(), s.wake(), null, Constants.NONE);
        }
    }

    private InterruptAction interrupted() {
        long d = deadline;
        if (d == 0 || System.nanoTime() - d < 0) return InterruptAction.CONTINUE;
        timedOut = true;
        return InterruptAction.SUSPEND; // unwinds the whole step: pcall can't keep a runaway script alive
    }

    // ---- exports

    /** Another resource calls one of ours. {@code done} gets Java values (copied out of this state). */
    void invokeExport(String fn, List<Object> args, ResourceHost.ExportDone done) {
        LuaValue f = exports instanceof LuaTable t ? t.rawget(fn) : Constants.NIL;
        if (!(f instanceof LuaFunction function)) {
            done.done(exports instanceof LuaTable ? "resource '" + name + "' has no export '" + fn + "'"
                    : "resource '" + name + "' exports nothing (its main.lua returns no table)", null);
            return;
        }
        Varargs luaArgs;
        try {
            luaArgs = toLuaArgs(args.toArray());
        } catch (LuaError e) {
            done.done(Values.readable(e.getMessage()), null);
            return;
        }
        spawn(function, luaArgs, "export " + fn, (err, results) -> {
            if (err != null) {
                done.done(err, null);
                return;
            }
            try {
                done.done(null, Values.toJava(this, results, 1));
            } catch (LuaError e) {
                done.done(Values.readable(e.getMessage()), null);
            }
        });
    }

    // ---- modules

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
        for (LuaFunction fn : List.copyOf(fns)) spawn(fn, luaArgs, "event " + module + "." + event, null);
    }

    Callback callback(LuaFunction fn) {
        return new LuaCallback(this, fn);
    }

    record LuaCallback(LuaRuntime runtime, LuaFunction function) implements Callback {
        @Override
        public void invoke(Object... args) {
            runtime.host.executor().execute(() -> {
                if (runtime.closed) return;
                try {
                    runtime.spawn(function, runtime.toLuaArgs(args), "callback", null);
                } catch (LuaError e) {
                    LOG.error("[{}] callback: {}", runtime.name, Values.readable(e.getMessage()));
                }
            });
        }
    }

    private ModuleRegistry.ModuleImpl module(String mod) throws LuaError {
        ModuleRegistry.ModuleImpl m = host.modules().find(mod);
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

    // ---- basics

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

    Varargs toLuaArgs(Object[] args) throws LuaError {
        LuaValue[] values = new LuaValue[args.length];
        for (int i = 0; i < args.length; i++) values[i] = Values.toLua(this, args[i]);
        return ValueFactory.varargsOf(values);
    }
}

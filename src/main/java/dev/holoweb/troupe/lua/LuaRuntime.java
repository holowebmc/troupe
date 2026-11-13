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
import org.squiddev.cobalt.LuaUserdata;
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

    /**
     * What a resource may use. One step past {@code stepMs} is cut and the resource goes on; past any other limit the
     * resource is stopped: {@code cpuMs} of Lua time or {@code windowAlloc} bytes allocated in {@code windowMs}, or
     * {@code stepAlloc} bytes allocated by one step. Allocation is what the JVM counts for the game thread while the
     * step runs (Cobalt doesn't track memory), garbage included.
     */
    public record Limits(long stepMs, long windowMs, long cpuMs, long stepAlloc, long windowAlloc) {
        public static final Limits DEFAULT = new Limits(TIME_LIMIT_MS, 10_000, 3_000, 256L << 20, 1L << 30);
    }

    private static final com.sun.management.ThreadMXBean THREADS =
            java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t
                    && t.isThreadAllocatedMemorySupported() && t.isThreadAllocatedMemoryEnabled() ? t : null;
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
                if (deadline == 0) continue;
                if (now - deadline > 0 || THREADS != null
                        && THREADS.getThreadAllocatedBytes(r.stepThread) - r.stepAllocStart > r.limits.stepAlloc()) r.state.interrupt();
            }
        }, 10, 10, TimeUnit.MILLISECONDS); // 10 ms: a script allocates tens of MB in that time, not GBs
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

    /** Wakes {@code task} at {@code due}: with nothing ({@code timer.wait}) or with {@code error} (a timeout). */
    private record Sleep(long due, Task task, int wake, String error) {}

    final String name;
    private final dev.holoweb.troupe.net.ResourceInfo info;
    final Side side;
    final ResourceHost host;
    private final Files files;
    private final LuaState state;
    private final LuaTable token = new LuaTable();
    /** Methods of player handles on the server; the prelude fills it ({@code player:message(...)}). */
    final LuaTable playerMethods = new LuaTable();
    /** Methods of entity handles ({@link EntityRef}); the prelude fills it ({@code entity:pos()}). */
    final LuaTable entityMethods = new LuaTable();
    final NetApi net;
    private LuaTable entityMeta;

    private final Map<ModuleRegistry.TypeImpl<?>, LuaTable> typeMetas = new HashMap<>();
    /** Functions wrapped by {@code typed(sig, fn)}. ponytail: kept until the resource stops. */
    private final Map<LuaValue, Sig> typed = new java.util.IdentityHashMap<>();

    /**
     * Editor definitions of what this resource offers the others: its exports ({@code resource('name').fn}) and its
     * net handlers. Signatures come from {@code typed}; otherwise the parameter names are all there is.
     */
    void stubs(StringBuilder out, List<String> resources, Map<String, List<String>> netOverloads) {
        net.stubs(netOverloads);
        if (!(exports instanceof LuaTable t) || name.contains("-")) return;
        String local = "res_" + name;
        StringBuilder fields = new StringBuilder(), functions = new StringBuilder();
        java.util.TreeMap<String, LuaValue> entries = new java.util.TreeMap<>();
        try {
            for (Varargs e = t.next(Constants.NIL); !e.first().isNil(); e = t.next(e.first()))
                if (e.first().isString()) entries.put(Values.str(e.first()), e.arg(2));
        } catch (LuaError e) {
            return;
        }
        entries.forEach((key, v) -> {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) return;
            if (!(v instanceof LuaFunction fn)) {
                fields.append("---@field ").append(key).append(' ').append(v.typeName()).append('\n');
                return;
            }
            Sig sig = typed.get(fn);
            if (sig == null && fn instanceof org.squiddev.cobalt.function.LuaClosure c) {
                org.squiddev.cobalt.Prototype p = c.getPrototype();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < p.parameters; i++) {
                    org.squiddev.cobalt.LuaString n = p.getLocalName(i + 1, 0);
                    names.add(n == null ? "arg" + (i + 1) : n.toString());
                }
                sig = Sig.named(names, p.isVarArg);
            }
            Sig.declare(functions, local, '.', key, sig);
        });
        out.append("---@class resource.").append(name).append('\n').append(fields).append("local ").append(local).append(" = {}\n").append(functions).append('\n');
        resources.add(name);
    }

    /** A Java object of a registered type ({@link dev.holoweb.troupe.api.Type}) as a Lua object. */
    LuaValue object(Object o, ModuleRegistry.TypeImpl<?> type) {
        return ValueFactory.userdataOf(o, typeMetas.computeIfAbsent(type, this::typeMeta));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LuaTable typeMeta(ModuleRegistry.TypeImpl type) {
        ModuleRegistry.TypeImpl<Object> t = type;
        LuaTable meta = new LuaTable();
        meta.rawset("__index", LibFunction.create((s, self, key) -> {
            if (!key.isString()) return Constants.NIL;
            String k = Values.str(key);
            Object o = self(t, self, k);
            var getter = t.getters.get(k);
            if (getter != null) return Values.toLua(this, guarded(t.name + "." + k, () -> getter.apply(o)));
            var method = t.methods.get(k);
            if (method == null) return Constants.NIL;
            // ponytail: a new function per lookup; cache per key if a script calls methods in a hot loop
            return LibFunction.createV((st, a) -> {
                Object target = self(t, a.arg(1), k);
                List<Object> args = Values.toJava(this, a, 2);
                return Values.toLua(this, guarded(t.name + ":" + k, () -> method.call(target, args(args))));
            });
        }));
        meta.rawset("__newindex", LibFunction.create((s, self, key, value) -> {
            String k = Values.str(key);
            var setter = t.setters.get(k);
            if (setter == null) throw new LuaError(t.name + " has no property '" + k + "' to set");
            Object o = self(t, self, k), v = Values.toJava(this, value, 0);
            guarded(t.name + "." + k, () -> {
                setter.accept(o, v);
                return null;
            });
            return Constants.NIL;
        }));
        meta.rawset("__tostring", LibFunction.create((s, self) -> Values.lua(String.valueOf(((LuaUserdata) self).instance))));
        meta.rawset("__eq", LibFunction.create((s, a, b) -> ValueFactory.valueOf(
                a instanceof LuaUserdata x && b instanceof LuaUserdata y && x.instance.equals(y.instance))));
        meta.rawset("__metatable", Values.lua(t.name)); // scripts can't read or swap it
        return meta;
    }

    private static Object self(ModuleRegistry.TypeImpl<?> t, LuaValue v, String member) throws LuaError {
        if (v instanceof LuaUserdata u && t.type.isInstance(u.instance)) return u.instance;
        throw new LuaError("call it as obj:" + member + "(...), on a " + t.name);
    }

    private Args args(List<Object> values) {
        return new Args(name, side, values, host.modules()::as);
    }

    /** Runs mod code for a script: whatever it throws becomes a Lua error that names {@code what}. */
    private Object guarded(String what, java.util.concurrent.Callable<Object> call) throws LuaError {
        long t0 = System.nanoTime();
        try {
            return call.call();
        } catch (LuaError e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new LuaError(what + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("[{}] {} threw", name, what, e);
            throw new LuaError(what + ": " + e);
        } finally {
            // Java can't be cut short like a script: its time counts against the resource (step), and it's named here.
            if (System.nanoTime() - t0 > SLOW_CALL) logThrottled("[" + name + "] " + what + " held the game for more than " + SLOW_CALL / 1_000_000 + " ms");
        }
    }

    private static final long SLOW_CALL = 50_000_000;

    /** Wakes a task waiting on mod code with its results, or with the error. */
    private void answer(Task task, int wake, String error, Object... results) {
        if (error != null) {
            wake(task, wake, error, Constants.NONE);
            return;
        }
        try {
            wake(task, wake, null, toLuaArgs(results));
        } catch (LuaError e) {
            wake(task, wake, Values.readable(e.getMessage()), Constants.NONE);
        }
    }

    /** Metatable of entity handles: the prelude's methods, and {@code entity.state} made on first use. */
    LuaTable entityMeta() {
        if (entityMeta == null) {
            entityMeta = new LuaTable();
            entityMeta.rawset("__index", LibFunction.create((s, self, key) -> {
                if (self instanceof LuaTable t && key.isString() && Values.str(key).equals("state")) {
                    LuaTable state = net.entityState(Values.str(t.rawget("id")));
                    t.rawset("state", state);
                    return state;
                }
                return entityMethods.rawget(key);
            }));
        }
        return entityMeta;
    }
    private final UiApi ui; // client only

    private final Limits limits;
    private volatile long stepThread, stepAllocStart; // of the running step, for the watchdog; set before deadline
    private volatile long deadline; // System.nanoTime() limit of the running step, 0 when idle
    private String cut; // why the interrupt handler stopped the running step
    private String fault; // over a limit: nothing more runs, and the host stops the resource on its next tick
    private long windowStart, windowNanos, windowAlloc, totalAlloc;
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

    LuaRuntime(String name, Side side, Files files, ResourceHost host, dev.holoweb.troupe.net.ResourceInfo info) throws LuaError {
        this.info = info;
        this.name = name;
        this.side = side;
        this.files = files;
        this.host = host;
        this.limits = host.limits();
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
                RegisteredFunction.of("has", (s, mod) -> {
                    ModuleRegistry.ModuleImpl m = host.modules().find(mod.checkString());
                    return ValueFactory.valueOf(m != null && host.reaches(info, m));
                }),
                RegisteredFunction.of("version", (s, mod) -> {
                    ModuleRegistry.ModuleImpl m = host.modules().find(mod.checkString());
                    return m == null || m.version() == null || !host.reaches(info, m) ? Constants.NIL : Values.lua(m.version());
                }),
                RegisteredFunction.ofV("call", (s, a) -> callModule(a.arg(1).checkString(), a.arg(2).checkString(), a, 3)),
                RegisteredFunction.of("get", (s, mod) -> moduleTable(mod.checkString())),
                RegisteredFunction.of("on", (s, mod, event, fn) -> {
                    listen(mod.checkString(), event.checkString(), fn.checkFunction());
                    return Constants.NIL;
                }),
        }));
        g.rawset("bit32", Bit32.table()); // Cobalt is Lua 5.2 without it
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
        // typed(sig, fn) in the prelude: remember the wrapper's signature, then check each call's arguments against it.
        hostApi.rawset("typed", LibFunction.create((s, sig, fn) -> {
            try {
                typed.put(fn.checkFunction(), Sig.parse(sig.checkString()));
            } catch (IllegalArgumentException e) {
                throw new LuaError(e.getMessage());
            }
            return Constants.NIL;
        }));
        hostApi.rawset("check", LibFunction.createV((s, a) -> {
            Sig sig = typed.get(a.arg(1));
            Object[] values = new Object[Math.max(0, a.count() - 1)];
            for (int i = 0; i < values.length; i++) {
                try {
                    values[i] = Values.toJava(this, a.arg(i + 2), 0);
                } catch (LuaError e) {
                    values[i] = a.arg(i + 2); // a coroutine, a foreign userdata: only "any" takes it
                }
            }
            try {
                sig.check(values.length, i -> values[i], host.modules());
                return Constants.NIL;
            } catch (IllegalArgumentException e) {
                return Values.lua(e.getMessage());
            }
        }));
        hostApi.rawset("async", LibFunction.create((s, mod) -> {
            LuaTable names = new LuaTable();
            int i = 1;
            for (String fn : module(mod.checkString()).asyncNames()) names.rawset(i++, Values.lua(fn));
            return names;
        }));
        handleMethods("player", playerMethods);
        handleMethods("entity", entityMethods);
        hostApi.rawset("token", token);
        hostApi.rawset("player_methods", playerMethods);
        hostApi.rawset("entity_methods", entityMethods);
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

    /** {@code handle:fn(...)} added by mods (Modules.method); the prelude runs after and its own methods win. */
    private void handleMethods(String kind, LuaTable methods) {
        host.modules().handleMethods.get(kind).forEach((fn, function) -> methods.rawset(fn, LibFunction.createV((s, a) -> {
            if (!(a.arg(1) instanceof LuaTable self)) throw new LuaError("call it as " + kind + ":" + fn + "(...)");
            List<Object> args = new ArrayList<>();
            args.add(Values.str(self.rawget("id")));
            args.addAll(Values.toJava(this, a, 2));
            return Values.toLua(this, guarded(kind + ":" + fn, () -> function.call(args(args))));
        })));
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
        fault = null; // on_stop still runs (a server script saves there), under the same step limits
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
                if (closed || fault != null) drop(s.task());
                else step(s.task(), s.args());
            }
        } finally {
            draining = false;
        }
    }

    private void step(Task task, Varargs args) {
        current = task;
        cut = null;
        long t0 = System.nanoTime();
        stepThread = Thread.currentThread().getId();
        stepAllocStart = allocated();
        deadline = t0 + TimeUnit.MILLISECONDS.toNanos(limits.stepMs());
        Varargs result = Constants.NONE;
        String error = null;
        try {
            result = LuaThread.run(task.thread, args);
            error = cut;
        } catch (LuaError e) {
            error = Values.readable(e.getMessage());
        } catch (OutOfMemoryError e) { // one huge allocation (string.rep) fails without taking the game down
            error = fault = "ran out of memory";
        } finally {
            deadline = 0;
            current = null;
            account(System.nanoTime(), t0, allocated() - stepAllocStart);
        }
        if (fault != null && error == null) error = fault;
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
                    sleeping.add(new Sleep(host.now() + (long) (ms * 1e6), task, wake, null));
                }
                case "call" -> {
                    // Whichever comes first wakes the task: the reply, or the timeout (a late reply is then dropped).
                    NetApi.Timeout t = net.call(rest, (err, results) -> wake(task, wake, err, results));
                    sleeping.add(new Sleep(host.now() + t.nanos(), task, wake, t.message()));
                }
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
                // A module function that answers later: it gets one more argument, a Callback to call with
                // (error) or (nil, results...), from any thread.
                case "module" -> {
                    String mod = Values.str(rest.arg(1)), fn = Values.str(rest.arg(2));
                    var async = module(mod).async(fn);
                    if (async != null) {
                        List<Object> in = Values.toJava(this, rest, 3);
                        var stage = (java.util.concurrent.CompletionStage<?>) guarded(mod + "." + fn, () -> async.call(args(in)));
                        if (stage == null) answer(task, wake, null);
                        else stage.whenComplete((value, failure) -> host.executor().execute(() -> {
                            Throwable e = failure instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : failure;
                            if (e == null) answer(task, wake, null, value);
                            else answer(task, wake, mod + "." + fn + ": " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()));
                        }));
                        break;
                    }
                    ModuleFunction function = module(mod).function(fn);
                    if (function == null) throw new LuaError("module '" + mod + "' has no function '" + fn + "'");
                    List<Object> args = new ArrayList<>(Values.toJava(this, rest, 3));
                    args.add((Callback) r -> host.executor().execute(() -> {
                        if (r.length > 0 && r[0] != null) answer(task, wake, mod + "." + fn + ": " + r[0]);
                        else answer(task, wake, null, java.util.Arrays.copyOfRange(r, Math.min(1, r.length), r.length));
                    }));
                    try {
                        function.call(args(args));
                    } catch (LuaError e) {
                        throw e;
                    } catch (Exception e) {
                        throw new LuaError(mod + "." + fn + ": " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()));
                    }
                }
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
        if (ui != null) ui.tick();
        Sleep s;
        while ((s = sleeping.peek()) != null && now - s.due() >= 0) {
            sleeping.poll();
            wake(s.task(), s.wake(), s.error(), Constants.NONE);
        }
    }

    private InterruptAction interrupted() {
        long d = deadline;
        if (d == 0) return InterruptAction.CONTINUE;
        if (allocated() - stepAllocStart > limits.stepAlloc()) cut = "stopped: too much memory in one call"; // account() faults it
        else if (System.nanoTime() - d < 0) return InterruptAction.CONTINUE;
        else cut = "stopped after running " + limits.stepMs() + " ms without waiting or returning";
        return InterruptAction.SUSPEND; // unwinds the whole step: pcall can't keep a runaway script alive
    }

    private static long allocated() {
        return THREADS == null ? 0 : THREADS.getCurrentThreadAllocatedBytes();
    }

    /** Adds a finished step to the window. Tumbling window: up to twice the budget fits across a boundary. */
    private void account(long now, long t0, long alloc) {
        if (now - windowStart > TimeUnit.MILLISECONDS.toNanos(limits.windowMs())) {
            windowStart = now;
            windowNanos = windowAlloc = 0;
        }
        windowNanos += now - t0;
        windowAlloc += alloc;
        totalAlloc += alloc;
        if (fault != null) return;
        if (alloc > limits.stepAlloc()) fault = "allocated more than " + (limits.stepAlloc() >> 20) + " MB in one call";
        else if (windowAlloc > limits.windowAlloc())
            fault = "allocated more than " + (limits.windowAlloc() >> 20) + " MB in " + limits.windowMs() / 1000 + " s";
        else if (windowNanos > TimeUnit.MILLISECONDS.toNanos(limits.cpuMs()))
            fault = "ran for more than " + limits.cpuMs() + " ms in " + limits.windowMs() / 1000 + " s";
    }

    /** Why the resource must stop (it went over a limit), or null. */
    String fault() {
        return fault;
    }

    void fault(String why) {
        if (fault == null) fault = why;
    }

    /** Bytes its steps allocated since it started. */
    long totalAlloc() {
        return totalAlloc;
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

    boolean listens(String module, String event) {
        return listeners.containsKey(module + "\0" + event);
    }

    /**
     * Runs the handlers now, one after another; any that returns {@code false} cancels. A handler that waits
     * ({@code timer.wait}...) goes on later and doesn't cancel. Errors are logged and don't cancel either.
     */
    boolean dispatchCancellable(String module, String event, Object[] args) {
        List<LuaFunction> fns = listeners.get(module + "\0" + event);
        if (fns == null) return true;
        Varargs luaArgs;
        try {
            luaArgs = toLuaArgs(args);
        } catch (LuaError e) {
            LOG.error("[{}] event {}.{}: {}", name, module, event, Values.readable(e.getMessage()));
            return true;
        }
        boolean[] allowed = {true};
        for (LuaFunction fn : List.copyOf(fns)) spawn(fn, luaArgs, "event " + module + "." + event, (err, results) -> {
            if (err != null) logThrottled("[" + name + "] event " + module + "." + event + ": " + err);
            else if (results.first() == Constants.FALSE) allowed[0] = false;
        });
        return allowed[0];
    }

    /** Events can fire every frame: the same error is logged at most once every 5 s. */
    private final Map<String, Long> recentErrors = new HashMap<>();

    void logThrottled(String message) {
        long now = System.nanoTime();
        Long last = recentErrors.get(message);
        if (last != null && now - last < 5_000_000_000L) return;
        if (recentErrors.size() > 256) recentErrors.clear();
        recentErrors.put(message, now);
        LOG.error(message);
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
        if (!host.reaches(info, m)) throw new LuaError("list the module '" + mod + "' under mods." + side.folder() + " in resource.json to use it");
        return m;
    }

    private Varargs callModule(String mod, String fn, Varargs args, int from) throws LuaError {
        ModuleFunction function = module(mod).function(fn);
        if (function == null) throw new LuaError("module '" + mod + "' has no function '" + fn + "'");
        List<Object> in = Values.toJava(this, args, from);
        return Values.toLua(this, guarded(mod + "." + fn, () -> function.call(args(in))));
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
        String full = args.arg(1).checkString();
        // require('base:shared.util'): shared/util.lua of a resource this one depends on, run in this resource's state.
        int colon = full.indexOf(':');
        String library = colon < 0 ? null : full.substring(0, colon), module = full.substring(colon + 1);
        String folder = module.substring(0, Math.max(0, module.indexOf('.')));
        if (!MODULE_NAME.matcher(module).matches() || !(folder.equals("shared") || library == null && folder.equals(side.folder())))
            return notFound(full, library == null ? " (modules are shared.x or " + side.folder() + ".x)" : " (another resource only lends its shared.x)");
        Files from = files;
        if (library != null) {
            if (!info.dependencies().contains(library)) return notFound(full, ": list '" + library + "' under dependencies in resource.json");
            from = host.filesOf(library);
            if (from == null) return notFound(full, ": the resource '" + library + "' isn't here");
        }
        Varargs chunk = compile(library == null ? name : library, from, module.replace('.', '/') + ".lua");
        return chunk != null ? chunk : notFound(full, "");
    }

    private static Varargs notFound(String module, String why) {
        return ValueFactory.varargsOf(Constants.NIL, ValueFactory.valueOf("module '" + module + "' not found" + why));
    }

    /** Null when the file doesn't exist; (nil, message) when it doesn't compile. */
    private Varargs compile(String path) throws LuaError {
        return compile(name, files, path);
    }

    private Varargs compile(String owner, Files from, String path) throws LuaError {
        byte[] source;
        try {
            source = from.read(path);
        } catch (IOException e) {
            throw new LuaError("can't read " + path + ": " + e.getMessage());
        }
        if (source == null) return null;
        try {
            return LoadState.load(state, new ByteArrayInputStream(source), "@" + owner + "/" + path, state.globals());
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

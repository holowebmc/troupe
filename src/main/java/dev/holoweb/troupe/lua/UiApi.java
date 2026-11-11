package dev.holoweb.troupe.lua;

import org.squiddev.cobalt.Constants;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.function.LibFunction;
import org.squiddev.cobalt.function.LuaFunction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The client's {@code ui} global: pages of the resource's {@code ui/} folder on screen or in the world.
 * <pre>
 * local v = ui.screen('index.html')
 * local p = ui.world('painel.html', { pos = { x, y, z }, yaw = 0, width = 2, size = { 1280, 720 }, billboard = false })
 * v:show()  v:hide()  v:focus(true)  v:send(name, data)  v:handle(name, fn(data) → result)  v:close()  p:move{...}
 * </pre>
 * The browser is created on the first {@code show} (each costs 50–100 MB); what is sent before waits for it.
 * Pages include {@code <script src="troupe.js">} for {@code troupe.call / emit / on}. Everything closes when the
 * resource stops.
 */
final class UiApi {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final byte[] TROUPE_JS;

    static {
        try (InputStream in = UiApi.class.getResourceAsStream("/troupe/troupe.js")) {
            TROUPE_JS = in.readAllBytes();
        } catch (IOException | NullPointerException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final LuaRuntime rt;
    private final UiBackend backend;
    private final LuaRuntime.Files files;
    private final List<View> views = new ArrayList<>();
    private boolean served;

    UiApi(LuaRuntime rt, LuaTable globals, UiBackend backend, LuaRuntime.Files files) {
        this.rt = rt;
        this.backend = backend;
        this.files = files;
        LuaTable ui = new LuaTable();
        ui.rawset("screen", LibFunction.create((s, page) -> new View(page(page), null).table));
        ui.rawset("world", LibFunction.create((s, page, opts) -> new View(page(page), opts.checkTable()).table));
        globals.rawset("ui", ui);
    }

    private String page(LuaValue page) throws LuaError {
        if (backend == null) throw new LuaError("ui needs the Vitrine mod on this client");
        String p = page.checkString();
        if (p.isEmpty() || p.startsWith("/") || p.contains("..") || p.contains("\\")) throw new LuaError("bad page '" + p + "': a path inside ui/");
        return p;
    }

    void close() {
        for (View v : List.copyOf(views)) v.close();
        if (served) backend.serve(rt.name, null);
        served = false;
    }

    private final class View {
        final LuaTable table = new LuaTable();
        private final String page;
        private final boolean world;
        private int pxWidth = 1280, pxHeight = 720;
        private double x, y, z;
        private float yaw, width = 2;
        private boolean billboard;
        private UiBackend.View view;
        private final List<String> pending = new ArrayList<>();
        private final Map<String, LuaFunction> handlers = new LinkedHashMap<>();
        private boolean closed;

        View(String page, LuaTable opts) throws LuaError {
            this.page = page;
            this.world = opts != null;
            if (world) {
                place(opts);
                LuaValue size = opts.rawget("size");
                if (size instanceof LuaTable s) {
                    pxWidth = s.rawget(1).checkInteger();
                    pxHeight = s.rawget(2).checkInteger();
                    if (pxWidth < 16 || pxHeight < 16 || pxWidth > 4096 || pxHeight > 4096) throw new LuaError("size must be 16..4096 pixels");
                }
                billboard = opts.rawget("billboard").toBoolean();
            }
            views.add(this);
            method("show", a -> {
                open().visible(true);
                return Constants.NONE;
            });
            method("hide", a -> {
                if (view != null) {
                    view.focus(false);
                    view.visible(false);
                }
                return Constants.NONE;
            });
            method("focus", a -> {
                if (world) throw new LuaError("focus is for ui.screen pages; world panels take the mouse when aimed at");
                boolean on = a.arg(2).isNil() || a.arg(2).toBoolean();
                UiBackend.View v = open();
                if (on) v.visible(true);
                v.focus(on);
                return Constants.NONE;
            });
            method("send", a -> {
                String name = name(a.arg(2));
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("troupe", name);
                message.put("data", Values.toJava(rt, a.arg(3), 0));
                String json;
                try {
                    json = Json.encode(message);
                } catch (IllegalArgumentException e) {
                    throw new LuaError(e.getMessage());
                }
                if (view != null) view.send(json);
                else if (pending.size() < 256) pending.add(json);
                else throw new LuaError("256 messages waiting for a page that was never shown");
                return Constants.NONE;
            });
            method("handle", a -> {
                String name = name(a.arg(2));
                LuaFunction fn = a.arg(3).checkFunction();
                if (handlers.containsKey(name)) throw new LuaError("handle: '" + name + "' already has a handler");
                handlers.put(name, fn);
                if (view != null) register(name, fn);
                return Constants.NONE;
            });
            method("close", a -> {
                close();
                return Constants.NONE;
            });
            if (world) method("move", a -> {
                place(a.arg(2).checkTable());
                if (view != null) view.move(x, y, z, yaw, width);
                return Constants.NONE;
            });
        }

        private interface Body {
            Varargs run(Varargs args) throws LuaError;
        }

        /** {@code view:name(...)}: args.arg(1) is the view itself. */
        private void method(String name, Body fn) {
            table.rawset(name, LibFunction.createV((s, a) -> {
                if (closed) throw new LuaError("this page was closed");
                return fn.run(a);
            }));
        }

        private void place(LuaTable opts) throws LuaError {
            if (!(opts.rawget("pos") instanceof LuaTable pos)) throw new LuaError("world panels need pos = { x, y, z }");
            x = pos.rawget(1).checkDouble();
            y = pos.rawget(2).checkDouble();
            z = pos.rawget(3).checkDouble();
            yaw = (float) opts.rawget("yaw").optDouble(yaw);
            width = (float) opts.rawget("width").optDouble(width);
            if (!(width > 0 && width <= 64)) throw new LuaError("width must be 0..64 blocks");
        }

        private String name(LuaValue v) throws LuaError {
            String name = v.checkString();
            if (!NAME.matcher(name).matches()) throw new LuaError("bad name '" + name + "' (letters, digits, _ . -)");
            return name;
        }

        private UiBackend.View open() {
            if (view != null) return view;
            if (!served) {
                served = true;
                backend.serve(rt.name, path -> path.equals("troupe.js") ? TROUPE_JS : files.read("ui/" + path));
            }
            view = world ? backend.world(rt.name, page, pxWidth, pxHeight, x, y, z, yaw, width, billboard) : backend.screen(rt.name, page);
            handlers.forEach(this::register);
            for (String json : pending) view.send(json);
            pending.clear();
            return view;
        }

        private void register(String name, LuaFunction fn) {
            view.on(name, (body, reply) -> {
                Varargs args;
                try {
                    args = ValueFactory.varargsOf(Values.toLua(rt, Json.decode(body)));
                } catch (IllegalArgumentException | LuaError e) {
                    reply.error("bad request: " + e.getMessage());
                    return;
                }
                rt.spawn(fn, args, "page " + name, (err, results) -> {
                    if (err != null) {
                        LuaRuntime.LOG.warn("[{}] page {}: {}", rt.name, name, err);
                        reply.error(err.lines().findFirst().orElse(err));
                        return;
                    }
                    try {
                        reply.ok(Json.encode(Values.toJava(rt, results.first(), 0)));
                    } catch (IllegalArgumentException | LuaError e) {
                        reply.error("'" + name + "' returned something that isn't JSON");
                    }
                });
            });
        }

        void close() {
            if (closed) return;
            closed = true;
            views.remove(this);
            if (view != null) view.close();
            view = null;
        }
    }
}

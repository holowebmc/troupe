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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The client's {@code ui} global: pages of the resource's {@code ui/} folder on screen or in the world.
 * <pre>
 * local v = ui.screen('index.html')
 * local p = ui.world('painel.html', { pos = { x, y, z }, yaw = 0, pitch = 0, width = 2, size = { 1280, 720 },
 *                                      billboard = false, distance = 64, volume = 1, range = 24 })
 * v:show()  v:hide()  v:send(name, data)  v:handle(name, fn(data) → result)  v:volume(0.5)  v:close()
 * v:focus(true)  v:focused()                  screens; Esc gives the keyboard back to the game
 * v:on_unfocus(fn)                            ... and then fn runs (not after v:focus(false) or v:hide())
 * p:move{ yaw = 90 }                          panels; any of the options above except size, the rest stays
 * </pre>
 * {@code pitch} 90 lays a panel face up (a table top), -90 face down. {@code distance}: hidden beyond it;
 * {@code range}: where the page's sound fades out. What Vitrine gives pages in CSS / HTML needs nothing here.
 * Slots, drawn by the game inside a page (Minecraft 26.2+; nothing shows before):
 * <pre>
 * &lt;div style="--vitrine-bind: troupe:slot" data-vitrine="arma"&gt;       the page: where, how big, what clips it
 * ui.slot('arma', { item = 'minecraft:diamond_sword', enchanted = true })   the script: what is in it
 * ui.slot('mochila5', { inventory = 5 })                 the player's own slot 5, always current
 * ui.slot('alvo', { entity = id, yaw = 30 })             an entity that is alive (id as in player.looking_at)
 * ui.slot('npc', { entity_type = 'minecraft:zombie', equipment = { head = 'minecraft:iron_helmet' } })
 * ui.slot('eu', { player = true })
 * ui.slot('forno', { block = 'minecraft:furnace', state = { lit = true } })
 * ui.slot('rotulo', { text = 'x3', color = 0xffffff, shadow = true, x = 14, y = 14 })
 * ui.slot('icone', { image = 'meu:textures/gui/x.png', w = 32, h = 32 })    ui.slot('fundo', { rect = true, color = 0x80000000 })
 * ui.slot('dois', { { item = 'minecraft:emerald', x = -20 }, { item = 'minecraft:diamond', x = 20 } })   up to 16 things
 * ui.slot('vivo', function(slot) return { item = slot.args[1], scale = 0.5 + slot.width / 400 } end)   asked every frame
 * ui.slot('arma', nil)
 * </pre>
 * Every thing is placed as a Java bind would place it, from the centre of the slot, one unit per pixel, y down and
 * negative z towards the viewer (what is nearer covers the rest):
 * {@code transform = { {'translate', x, y, z}, {'rotate', 'y', 45}, {'scale', 2} }} (in order, as on a PoseStack), then
 * {@code x}, {@code y}, {@code z}, then {@code scale} (a number or {@code {x, y, z}}), then {@code yaw}, {@code pitch}
 * and {@code roll} in degrees, to which the page adds its own: {@code data-vitrine="alvo 30 10"} turns the thing by 30
 * and 10 degrees (e.g. as the mouse drags). Items, blocks and entities are fitted to the slot; {@code fit = false}
 * draws them at one pixel per block instead, for {@code scale} to decide. {@code light} is 0..15 (15 by default) and
 * {@code lighting} one of 'entity', 'flat', '3d', 'level', 'skin' (one per slot: the last thing's). Items take
 * {@code enchanted}, {@code damage}, {@code model} (custom model data) and {@code item_model} (the id of any item
 * model the game has loaded: a mod's, or one in the resource's assets); images {@code w, h, u0, v0, u1, v1, color}; rectangles
 * {@code w, h, color}; texts {@code color, shadow, background}. Entities take {@code body_yaw}, {@code head_yaw},
 * {@code head_pitch}, {@code walk}, {@code walk_speed}, {@code swing}, {@code pose} ('crouching', 'sleeping'...),
 * {@code crouching}, {@code upside_down}, {@code hurt}, {@code fire}, {@code age}, and {@code natural = true} to keep
 * the way they face in the world; stand-ins also {@code equipment} and {@code baby}. A function gets
 * {@code { width, height, args }} ({@code args}: what follows the key in {@code data-vitrine}) and returns a thing,
 * a list or nil; it must not wait. Slots belong to the resource: any of its pages shows them.
 * <p>
 * The browser is created on the first {@code show} (each costs 50–100 MB); what is sent before waits for it.
 * Pages include {@code <script src="troupe.js">} for {@code troupe.call / emit / on}. Everything closes when the
 * resource stops.
 */
final class UiApi {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Set<String> KINDS = Set.of("item", "inventory", "entity", "entity_type", "player", "block", "text", "image", "rect");
    private static final int MAX_SLOTS = 256, MAX_THINGS = 16;
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
    /** key → what it shows: a list of things, or the function that returns them. */
    private final Map<String, Object> slots = new LinkedHashMap<>();
    private boolean slotsOn;

    UiApi(LuaRuntime rt, LuaTable globals, UiBackend backend, LuaRuntime.Files files) {
        this.rt = rt;
        this.backend = backend;
        this.files = files;
        LuaTable ui = new LuaTable();
        ui.rawset("screen", LibFunction.create((s, page) -> new View(page(page), null).table));
        ui.rawset("world", LibFunction.create((s, page, opts) -> new View(page(page), opts.checkTable()).table));
        ui.rawset("slot", LibFunction.create((s, key, what) -> {
            if (backend == null) throw new LuaError("ui needs the Vitrine mod on this client");
            String k = key.checkString();
            if (!NAME.matcher(k).matches()) throw new LuaError("bad slot '" + k + "' (letters, digits, _ . -)");
            if (what.isNil()) {
                slots.remove(k);
                return Constants.NIL;
            }
            if (!slots.containsKey(k) && slots.size() >= MAX_SLOTS) throw new LuaError("at most " + MAX_SLOTS + " slots per resource");
            slots.put(k, what instanceof LuaFunction fn ? fn : things(Values.toJava(rt, what.checkTable(), 0)));
            if (!slotsOn) {
                slotsOn = true;
                backend.slots(rt.name, this::slot);
            }
            return Constants.NIL;
        }));
        globals.rawset("ui", ui);
    }

    /** A thing or a list of things, as Java maps; each is exactly one of {@link #KINDS}. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> things(Object value) throws LuaError {
        List<?> list = value == null ? List.of() : value instanceof List<?> l ? l : List.of(value);
        if (list.size() > MAX_THINGS) throw new LuaError("at most " + MAX_THINGS + " things in a slot");
        for (Object thing : list) {
            if (!(thing instanceof Map<?, ?> m) || KINDS.stream().filter(m::containsKey).count() != 1)
                throw new LuaError("a slot shows one of item, inventory, entity, entity_type, player, block, text, image or rect (e.g. { item = id }), or a list of them");
        }
        return (List<Map<String, Object>>) list;
    }

    /** What slot {@code key} shows now; a function that fails or waits shows nothing. Render thread, every frame. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> slot(String key, int width, int height, List<String> args) {
        Object what = slots.get(key);
        if (!(what instanceof LuaFunction fn)) return what == null ? List.of() : (List<Map<String, Object>>) what;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("width", width);
        info.put("height", height);
        info.put("args", args);
        List<Map<String, Object>>[] out = new List[] {List.of()};
        try {
            rt.spawn(fn, ValueFactory.varargsOf(Values.toLua(rt, info)), "slot " + key, (err, results) -> {
                try {
                    if (err != null) throw new LuaError(err);
                    out[0] = things(Values.toJava(rt, results.first(), 0));
                } catch (LuaError e) {
                    rt.logThrottled("[" + rt.name + "] slot " + key + ": " + e.getMessage());
                }
            });
        } catch (LuaError e) {
            rt.logThrottled("[" + rt.name + "] slot " + key + ": " + e.getMessage());
        }
        return out[0];
    }

    private String page(LuaValue page) throws LuaError {
        if (backend == null) throw new LuaError("ui needs the Vitrine mod on this client");
        String p = page.checkString();
        if (p.isEmpty() || p.startsWith("/") || p.contains("..") || p.contains("\\")) throw new LuaError("bad page '" + p + "': a path inside ui/");
        return p;
    }

    /** Every game tick: tells pages that lost the keyboard without asking (Esc, another screen). */
    void tick() {
        for (View v : List.copyOf(views)) v.tick();
    }

    void close() {
        for (View v : List.copyOf(views)) v.close();
        if (served) backend.serve(rt.name, null);
        served = false;
        if (slotsOn) backend.slots(rt.name, null);
        slotsOn = false;
        slots.clear();
    }

    private final class View {
        final LuaTable table = new LuaTable();
        private final String page;
        private final boolean world;
        private int pxWidth = 1280, pxHeight = 720;
        private double x, y, z;
        private float yaw, pitch, width = 2, distance = 64, volume = 1, range = 24;
        private boolean billboard;
        private UiBackend.View view;
        private final List<String> pending = new ArrayList<>();
        private final Map<String, LuaFunction> handlers = new LinkedHashMap<>();
        private boolean closed;
        private LuaFunction onUnfocus;
        private boolean wantsFocus, hadFocus; // asked by the script; seen focused since (the browser may start later)

        View(String page, LuaTable opts) throws LuaError {
            this.page = page;
            this.world = opts != null;
            if (world) {
                if (!(opts.rawget("pos") instanceof LuaTable)) throw new LuaError("world panels need pos = { x, y, z }");
                place(opts);
                LuaValue size = opts.rawget("size");
                if (size instanceof LuaTable s) {
                    pxWidth = s.rawget(1).checkInteger();
                    pxHeight = s.rawget(2).checkInteger();
                    if (pxWidth < 16 || pxHeight < 16 || pxWidth > 4096 || pxHeight > 4096) throw new LuaError("size must be 16..4096 pixels");
                }
            }
            views.add(this);
            method("show", a -> {
                open().visible(true);
                return Constants.NONE;
            });
            method("hide", a -> {
                wantsFocus = hadFocus = false;
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
                wantsFocus = on;
                hadFocus = false;
                v.focus(on);
                return Constants.NONE;
            });
            if (!world) method("on_unfocus", a -> {
                onUnfocus = a.arg(2).checkFunction();
                return Constants.NONE;
            });
            method("focused", a -> ValueFactory.valueOf(view != null && view.focused()));
            method("volume", a -> {
                volume = unit(a.arg(2), "volume");
                if (view != null && world) place();
                else if (view != null) view.volume(volume);
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
                if (view != null) place();
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
            // Everything is read and checked before anything changes: a bad move leaves the panel as it was.
            float width = (float) opts.rawget("width").optDouble(this.width);
            float distance = (float) opts.rawget("distance").optDouble(this.distance);
            float range = (float) opts.rawget("range").optDouble(this.range);
            float volume = opts.rawget("volume").isNil() ? this.volume : unit(opts.rawget("volume"), "volume");
            float yaw = (float) opts.rawget("yaw").optDouble(this.yaw);
            float pitch = (float) opts.rawget("pitch").optDouble(this.pitch);
            if (!(width > 0 && width <= 64)) throw new LuaError("width must be 0..64 blocks");
            if (!(distance > 0 && distance <= 512)) throw new LuaError("distance must be 0..512 blocks");
            if (!(range > 0 && range <= 512)) throw new LuaError("range must be 0..512 blocks");
            if (opts.rawget("pos") instanceof LuaTable pos) {
                double px = pos.rawget(1).checkDouble(), py = pos.rawget(2).checkDouble(), pz = pos.rawget(3).checkDouble();
                x = px;
                y = py;
                z = pz;
            }
            if (!opts.rawget("billboard").isNil()) billboard = opts.rawget("billboard").toBoolean();
            this.yaw = yaw;
            this.pitch = pitch;
            this.width = width;
            this.distance = distance;
            this.range = range;
            this.volume = volume;
        }

        private void place() {
            view.place(x, y, z, yaw, pitch, width, billboard, distance, volume, range);
        }

        private float unit(LuaValue v, String what) throws LuaError {
            double d = v.checkDouble();
            if (!(d >= 0 && d <= 1)) throw new LuaError(what + " must be 0..1");
            return (float) d;
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
            view = world ? backend.world(rt.name, page, pxWidth, pxHeight) : backend.screen(rt.name, page);
            if (world) place();
            else view.volume(volume);
            handlers.forEach(this::register);
            for (String json : pending) view.send(json);
            pending.clear();
            return view;
        }

        void tick() {
            if (!wantsFocus) return;
            if (view.focused()) {
                hadFocus = true;
            } else if (hadFocus) {
                wantsFocus = hadFocus = false;
                if (onUnfocus != null) rt.spawn(onUnfocus, Constants.NONE, "on_unfocus", (err, r) -> {
                    if (err != null) LuaRuntime.LOG.warn("[{}] on_unfocus: {}", rt.name, err);
                });
            }
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

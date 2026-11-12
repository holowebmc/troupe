package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else
/*import net.minecraft.client.gui.GuiGraphics;*/

/**
 * Client extras, without Vitrine (the prelude has nothing to add):
 * <pre>
 * hud.text(id, text, { x, y, color = 0xffffff, anchor = 'top_left', shadow = true, scale = 1, layer = 0 })
 * hud.rect(id, { x, y, w, h, color = 0x80000000, anchor, layer })
 * hud.image(id, texture, { x, y, w, h, u = 0, v = 0, tex_w = w, tex_h = h, color = 0xffffffff, anchor, layer })
 *   (texture: 'ns:textures/gui/x.png' from a resource's assets; by default the whole image fills w × h)
 * hud.remove(id)  hud.clear()
 *   anchors: top_left, top, top_right, left, center, right, bottom_left, bottom, bottom_right; higher layers on top
 * render.on_hud(fn(screen))   every frame (screen = {width, height}); inside it render.rect / render.text / render.image
 *                             take the same options and draw for that frame only
 * hud.toast(title, text?)
 * effects.sound(id, { volume, pitch })   effects.particles(id, x, y, z, { count, spread, speed })   (only for this player)
 * player.looking_at() → { kind = 'block', x, y, z, id, face } | { kind = 'entity', id, type } | nil
 * </pre>
 * Texts belong to their resource and go away when it stops. Render thread.
 */
final class ClientHud {
    private static final List<String> ANCHORS = List.of("top_left", "top", "top_right", "left", "center", "right", "bottom_left", "bottom", "bottom_right");
    private static final int MAX_ITEMS = 256, MAX_FRAME_ITEMS = 2048, MAX_PARTICLES = 200;

    /** One thing on the HUD: a text, a rectangle or an image (texture != null). */
    private record Item(String text, Identifier texture, int x, int y, int w, int h, int u, int v, int texW, int texH,
                        int color, int anchor, boolean shadow, float scale, int layer) {}

    /** resource → (id → item), in insertion order. */
    private final Map<String, Map<String, Item>> items = new LinkedHashMap<>();
    private final List<Item> frame = new ArrayList<>();
    private final ModuleRegistry modules;
    private final Module render;
    private boolean collecting;

    ClientHud(ModuleRegistry modules, ResourceHost host) {
        this.modules = modules;
        modules.global("hud")
                .function("text", a -> keep(a, text(a.string(1), opts(a, 2))))
                .function("rect", a -> keep(a, rect(opts(a, 1))))
                .function("image", a -> keep(a, image(a.string(1), opts(a, 2))))
                .function("remove", a -> {
                    Map<String, Item> mine = items.get(a.resource());
                    if (mine != null) mine.remove(a.string(0));
                    return null;
                })
                .function("clear", a -> {
                    items.remove(a.resource());
                    return null;
                })
                .function("toast", a -> {
                    Component title = Component.literal(a.string(0)), text = a.get(1) == null ? null : Component.literal(a.string(1));
                    //? if >=26.1 {
                    SystemToast.add(Minecraft.getInstance().gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text);
                    //?} else
                    /*SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, title, text);*/
                    return null;
                });

        modules.global("effects")
                .function("sound", a -> {
                    Map<String, Object> o = a.get(1) == null ? Map.of() : a.table(1);
                    SoundEvent sound = SoundEvent.createVariableRangeEvent(ServerApi.id(a.string(0)));
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound,
                            (float) Math.max(0.01, Math.min(2, number(o, "pitch", 1))), (float) Math.max(0, Math.min(1, number(o, "volume", 1)))));
                    return null;
                })
                .function("particles", a -> {
                    var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(ServerApi.id(a.string(0)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown particle '" + a.string(0) + "'"));
                    if (!(type instanceof SimpleParticleType simple))
                        throw new IllegalArgumentException("'" + a.string(0) + "' needs extra data; only simple particles for now");
                    var level = Minecraft.getInstance().level;
                    if (level == null) return null;
                    Map<String, Object> o = a.get(4) == null ? Map.of() : a.table(4);
                    int count = (int) number(o, "count", 1);
                    if (count < 1 || count > MAX_PARTICLES) throw new IllegalArgumentException("count must be 1.." + MAX_PARTICLES);
                    double spread = Math.max(0, Math.min(16, number(o, "spread", 0))), speed = number(o, "speed", 0);
                    var r = ThreadLocalRandom.current();
                    for (int i = 0; i < count; i++)
                        level.addParticle(simple, a.number(1) + r.nextGaussian() * spread, a.number(2) + r.nextGaussian() * spread,
                                a.number(3) + r.nextGaussian() * spread, r.nextGaussian() * speed, r.nextGaussian() * speed, r.nextGaussian() * speed);
                    return null;
                });

        render = modules.global("render")
                .function("rect", a -> now(rect(opts(a, 0))))
                .function("text", a -> now(text(a.string(0), opts(a, 1))))
                .function("image", a -> now(image(a.string(0), opts(a, 1))))
                .event("hud");

        modules.global("player").function("looking_at", a -> looking());

        host.onStop(items::remove);
    }

    void clear() {
        items.clear();
    }

    private Object keep(Args a, Item item) {
        Map<String, Item> mine = items.computeIfAbsent(a.resource(), r -> new LinkedHashMap<>());
        if (!mine.containsKey(a.string(0)) && mine.size() >= MAX_ITEMS) throw new IllegalArgumentException("at most " + MAX_ITEMS + " HUD items per resource");
        mine.put(a.string(0), item);
        return null;
    }

    private Object now(Item item) {
        if (!collecting) throw new IllegalArgumentException("render.rect/text/image only work inside render.on_hud");
        if (frame.size() < MAX_FRAME_ITEMS) frame.add(item);
        return null;
    }

    private static Map<String, Object> opts(Args a, int i) {
        return a.get(i) == null ? Map.of() : a.table(i);
    }

    private static Item text(String text, Map<String, Object> o) {
        if (text.length() > 1024) throw new IllegalArgumentException("text too long (1024 max)");
        return new Item(text, null, (int) number(o, "x", 0), (int) number(o, "y", 0), 0, 0, 0, 0, 0, 0, color(o, 0xFFFFFF), anchor(o),
                !Boolean.FALSE.equals(o.get("shadow")), (float) Math.max(0.1, Math.min(16, number(o, "scale", 1))), (int) number(o, "layer", 0));
    }

    private static Item rect(Map<String, Object> o) {
        return new Item(null, null, (int) number(o, "x", 0), (int) number(o, "y", 0), size(o, "w", 0), size(o, "h", 0), 0, 0, 0, 0,
                o.containsKey("color") ? color(o, 0) : 0x80000000, anchor(o), false, 1, (int) number(o, "layer", 0));
    }

    private static Item image(String texture, Map<String, Object> o) {
        int w = size(o, "w", 16), h = size(o, "h", 16);
        return new Item(null, ServerApi.id(texture), (int) number(o, "x", 0), (int) number(o, "y", 0), w, h, (int) number(o, "u", 0), (int) number(o, "v", 0),
                size(o, "tex_w", w), size(o, "tex_h", h), o.containsKey("color") ? color(o, 0) : 0xFFFFFFFF, anchor(o), false, 1, (int) number(o, "layer", 0));
    }

    private static int size(Map<String, Object> o, String key, int fallback) {
        double v = number(o, key, fallback);
        if (v < 0 || v > 8192) throw new IllegalArgumentException(key + " must be 0..8192");
        return (int) v;
    }

    private static int anchor(Map<String, Object> o) {
        String anchor = o.get("anchor") == null ? "top_left" : String.valueOf(o.get("anchor"));
        if (!ANCHORS.contains(anchor)) throw new IllegalArgumentException("anchor is one of " + String.join(", ", ANCHORS));
        return ANCHORS.indexOf(anchor);
    }

    /** 0xRRGGBB is opaque; 0xAARRGGBB keeps its alpha; {@code alpha} (0..1) overrides it. */
    static int color(Map<String, Object> o, int fallback) {
        long c = o.get("color") instanceof Number n ? n.longValue() : fallback;
        int argb = c > 0xFFFFFF ? (int) c : (int) (0xFF000000L | c);
        if (o.get("alpha") instanceof Number a) argb = (argb & 0xFFFFFF) | ((int) Math.round(Math.max(0, Math.min(1, a.doubleValue())) * 255) << 24);
        return argb;
    }

    private static Map<String, Object> looking() {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS || mc.level == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (hit instanceof BlockHitResult b) {
            out.put("kind", "block");
            out.put("x", b.getBlockPos().getX());
            out.put("y", b.getBlockPos().getY());
            out.put("z", b.getBlockPos().getZ());
            out.put("id", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b.getBlockPos()).getBlock()).toString());
            out.put("face", b.getDirection().getName());
        } else if (hit instanceof EntityHitResult e) {
            out.put("kind", "entity");
            out.put("id", e.getEntity().getUUID().toString());
            out.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString());
        }
        return out;
    }

    //? if >=26.1 {
    void draw(GuiGraphicsExtractor g) {
    //?} else
    /*void draw(GuiGraphics g) {*/
        int w = g.guiWidth(), h = g.guiHeight();
        frame.clear();
        if (modules.listens(render, "hud")) {
            Map<String, Object> screen = new LinkedHashMap<>();
            screen.put("width", w);
            screen.put("height", h);
            collecting = true;
            try {
                modules.emitCancellable(render, "hud", screen);
            } finally {
                collecting = false;
            }
        }
        if (items.isEmpty() && frame.isEmpty()) return;
        List<Item> all = new ArrayList<>();
        for (Map<String, Item> mine : items.values()) all.addAll(mine.values());
        all.sort(java.util.Comparator.comparingInt(Item::layer)); // stable: same layer keeps creation order
        all.addAll(frame);
        Font font = Minecraft.getInstance().font;
        for (Item t : all) {
            boolean isText = t.text() != null;
            int iw = isText ? (int) (font.width(t.text()) * t.scale()) : t.w(), ih = isText ? (int) (font.lineHeight * t.scale()) : t.h();
            int col = t.anchor() % 3, row = t.anchor() / 3;
            int x = (col == 0 ? 0 : col == 1 ? (w - iw) / 2 : w - iw) + t.x();
            int y = (row == 0 ? 0 : row == 1 ? (h - ih) / 2 : h - ih) + t.y();
            if (isText) text(g, font, t, x, y);
            else if (t.texture() == null) g.fill(x, y, x + t.w(), y + t.h(), t.color());
            else image(g, t, x, y);
        }
    }

    //? if >=26.1 {
    private static void text(GuiGraphicsExtractor g, Font font, Item t, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(t.scale(), t.scale());
        g.text(font, t.text(), 0, 0, t.color(), t.shadow());
        g.pose().popMatrix();
    }

    private static void image(GuiGraphicsExtractor g, Item t, int x, int y) {
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, t.texture(), x, y, t.u(), t.v(), t.w(), t.h(), t.texW(), t.texH(), t.color());
    }
    //?} else {
    /*private static void text(GuiGraphics g, Font font, Item t, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(t.scale(), t.scale(), 1);
        g.drawString(font, t.text(), 0, 0, t.color(), t.shadow());
        g.pose().popPose();
    }

    private static void image(GuiGraphics g, Item t, int x, int y) {
        int c = t.color();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f, (c >>> 24) / 255f);
        g.blit(t.texture(), x, y, t.u(), t.v(), t.w(), t.h(), t.texW(), t.texH());
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
    }
    *///?}

    private static double number(Map<String, Object> o, String key, double fallback) {
        Object v = o.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n)) throw new IllegalArgumentException(key + " must be a number");
        return n.doubleValue();
    }
}

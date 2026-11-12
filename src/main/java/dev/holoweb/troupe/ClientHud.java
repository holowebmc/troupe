package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

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
 * hud.text(id, text, { x, y, color = 0xffffff, anchor = 'top_left', shadow = true })  hud.remove(id)  hud.clear()
 *   anchors: top_left, top, top_right, left, center, right, bottom_left, bottom, bottom_right
 * hud.toast(title, text?)
 * effects.sound(id, { volume, pitch })   effects.particles(id, x, y, z, { count, spread, speed })   (only for this player)
 * player.looking_at() → { kind = 'block', x, y, z, id, face } | { kind = 'entity', id, type } | nil
 * </pre>
 * Texts belong to their resource and go away when it stops. Render thread.
 */
final class ClientHud {
    private static final List<String> ANCHORS = List.of("top_left", "top", "top_right", "left", "center", "right", "bottom_left", "bottom", "bottom_right");
    private static final int MAX_TEXTS = 64, MAX_PARTICLES = 200;

    private record Text(String text, int x, int y, int color, int anchor, boolean shadow) {}

    /** resource → (id → text), in insertion order. */
    private final Map<String, Map<String, Text>> texts = new LinkedHashMap<>();

    ClientHud(ModuleRegistry modules, ResourceHost host) {
        modules.global("hud")
                .function("text", a -> {
                    Map<String, Object> o = a.get(2) == null ? Map.of() : a.table(2);
                    String anchor = o.get("anchor") == null ? "top_left" : String.valueOf(o.get("anchor"));
                    if (!ANCHORS.contains(anchor)) throw new IllegalArgumentException("anchor is one of " + String.join(", ", ANCHORS));
                    Map<String, Text> mine = texts.computeIfAbsent(a.resource(), r -> new LinkedHashMap<>());
                    if (!mine.containsKey(a.string(0)) && mine.size() >= MAX_TEXTS) throw new IllegalArgumentException("at most " + MAX_TEXTS + " texts per resource");
                    mine.put(a.string(0), new Text(a.string(1), (int) number(o, "x", 0), (int) number(o, "y", 0),
                            0xFF000000 | ((int) number(o, "color", 0xFFFFFF) & 0xFFFFFF), ANCHORS.indexOf(anchor), !Boolean.FALSE.equals(o.get("shadow"))));
                    return null;
                })
                .function("remove", a -> {
                    Map<String, Text> mine = texts.get(a.resource());
                    if (mine != null) mine.remove(a.string(0));
                    return null;
                })
                .function("clear", a -> {
                    texts.remove(a.resource());
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

        modules.global("player").function("looking_at", a -> looking());

        host.onStop(texts::remove);
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
        if (texts.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        int w = g.guiWidth(), h = g.guiHeight();
        for (Map<String, Text> mine : texts.values()) for (Text t : mine.values()) {
            int col = t.anchor() % 3, row = t.anchor() / 3, tw = font.width(t.text());
            int x = (col == 0 ? 0 : col == 1 ? (w - tw) / 2 : w - tw) + t.x();
            int y = (row == 0 ? 0 : row == 1 ? (h - font.lineHeight) / 2 : h - font.lineHeight) + t.y();
            //? if >=26.1 {
            g.text(font, t.text(), x, y, t.color(), t.shadow());
            //?} else
            /*g.drawString(font, t.text(), x, y, t.color(), t.shadow());*/
        }
    }

    private static double number(Map<String, Object> o, String key, double fallback) {
        Object v = o.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n)) throw new IllegalArgumentException(key + " must be a number");
        return n.doubleValue();
    }
}

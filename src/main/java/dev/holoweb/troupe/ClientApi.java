package dev.holoweb.troupe;

import com.mojang.blaze3d.platform.InputConstants;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Troupe's client API, as global modules:
 * <pre>
 * player.name()  player.pos()                      the local player
 * chat.print(text)                                 a line in the local chat
 * keys.bind('b', fn)   keys.bind('left.alt', fn)   press while no screen is open (key names after "key.keyboard.")
 * </pre>
 * Render thread only.
 */
final class ClientApi {
    /** Watched keys (name → key code) and whether each was down last tick. */
    private final Map<String, Integer> keys = new LinkedHashMap<>();
    private final Map<String, Boolean> down = new LinkedHashMap<>();
    private final Module keyModule;

    ClientApi(ModuleRegistry modules) {
        modules.global("player")
                .function("name", a -> Compat.name(me().getGameProfile()))
                .function("pos", a -> {
                    Vec3 v = me().position();
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", v.x);
                    pos.put("y", v.y);
                    pos.put("z", v.z);
                    pos.put("yaw", (double) me().getYRot());
                    pos.put("pitch", (double) me().getXRot());
                    return pos;
                });

        // The entities this client knows about, nearest first: what state.entity(id) and draw.* can point at.
        modules.global("entities").function("near", a -> {
            double radius = Math.min(128, a.optNumber(0, 16));
            Vec3 here = me().position();
            java.util.List<net.minecraft.world.entity.Entity> found = new java.util.ArrayList<>();
            for (var e : Minecraft.getInstance().level.entitiesForRendering())
                if (e != me() && e.position().distanceToSqr(here) <= radius * radius) found.add(e);
            found.sort(java.util.Comparator.comparingDouble(e -> e.position().distanceToSqr(here)));
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (var e : found.subList(0, Math.min(256, found.size()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getStringUUID());
                m.put("type", String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
                m.put("x", e.getX());
                m.put("y", e.getY());
                m.put("z", e.getZ());
                out.add(m);
            }
            return out;
        });

        modules.global("chat").function("print", a -> {
            me();
            ClientCompat.chat(Component.literal(a.string(0)));
            return null;
        });

        // keys.bind(key, fn, release) is in the prelude: it calls watch(key) and listens to "press" and "release".
        keyModule = modules.global("keys")
                .function("watch", a -> {
                    String name = a.string(0);
                    InputConstants.Key key = InputConstants.getKey("key.keyboard." + name);
                    if (key == InputConstants.UNKNOWN) throw new IllegalArgumentException("unknown key '" + name + "' (e.g. 'b', 'f6', 'left.alt')");
                    keys.put(name, key.getValue());
                    return null;
                })
                .event("press")
                .event("release");
    }

    /** Watched keys whose press went to the scripts and that are still down: they owe a release. */
    private final java.util.Set<String> pressed = new java.util.HashSet<>();

    /**
     * Every client tick: a watched key that went down, with no screen open, is a press; letting go of a pressed key is
     * a release, screen or not, so a script that shows something while a key is held always hears the end.
     */
    void tick() {
        boolean free = ClientCompat.screen() == null && Minecraft.getInstance().player != null;
        keys.forEach((name, code) -> {
            boolean now = ClientCompat.keyDown(code);
            if (now && !down.getOrDefault(name, false) && free) {
                pressed.add(name);
                keyModule.emit("press", name);
            } else if (!now && pressed.remove(name)) {
                keyModule.emit("release", name);
            }
            down.put(name, now);
        });
    }

    private static LocalPlayer me() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("not in a world");
        return p;
    }
}

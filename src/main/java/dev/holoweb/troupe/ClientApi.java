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
                .function("name", a -> me().getGameProfile().name())
                .function("pos", a -> {
                    Vec3 v = me().position();
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", v.x);
                    pos.put("y", v.y);
                    pos.put("z", v.z);
                    pos.put("yaw", (double) me().getYRot());
                    return pos;
                });

        modules.global("chat").function("print", a -> {
            me().sendSystemMessage(Component.literal(a.string(0)));
            return null;
        });

        // keys.bind(key, fn) is in the prelude: it calls watch(key) and listens to "press".
        keyModule = modules.global("keys")
                .function("watch", a -> {
                    String name = a.string(0);
                    InputConstants.Key key = InputConstants.getKey("key.keyboard." + name);
                    if (key == InputConstants.UNKNOWN) throw new IllegalArgumentException("unknown key '" + name + "' (e.g. 'b', 'f6', 'left.alt')");
                    keys.put(name, key.getValue());
                    return null;
                })
                .event("press");
    }

    /** Every client tick: a watched key that went down, with no screen open, is a press. */
    void tick() {
        boolean free = Minecraft.getInstance().gui.screen() == null && Minecraft.getInstance().player != null;
        keys.forEach((name, code) -> {
            boolean now = InputConstants.isKeyDown(code);
            if (now && !down.getOrDefault(name, false) && free) keyModule.emit("press", name);
            down.put(name, now);
        });
    }

    private static LocalPlayer me() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw new IllegalStateException("not in a world");
        return p;
    }
}

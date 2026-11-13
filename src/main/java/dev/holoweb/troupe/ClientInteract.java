package dev.holoweb.troupe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client half of {@code interact.add}: the server's targets arrive as global state (keys
 * {@code troupe:interact:<id>}); the one the player looks at from close enough shows a prompt with the Interact key,
 * and pressing it tells the server, which checks again before running the script.
 */
final class ClientInteract {
    static final String PREFIX = "troupe:interact:", EVENT = "troupe:interact";

    private record Target(String resource, long id, String kind, double x, double y, double z, String entity, double radius,
                          String label, double distance, String dim) {}

    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final ResourceHost host;
    private final ClientHud hud;

    ClientInteract(ResourceHost host, ClientHud hud) {
        this.host = host;
        this.hud = hud;
        host.onState((resource, key, json) -> {
            if (!key.startsWith(PREFIX)) return;
            String name = resource + ":" + key.substring(PREFIX.length());
            if (json == null) {
                targets.remove(name);
                return;
            }
            try { // the server's word, but still checked: a bad target is just not shown
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String label = o.get("label").getAsString();
                targets.put(name, new Target(resource, Long.parseLong(key.substring(PREFIX.length())), o.get("kind").getAsString(),
                        num(o, "x"), num(o, "y"), num(o, "z"), o.has("entity") ? o.get("entity").getAsString() : null, num(o, "radius"),
                        label.length() > 64 ? label.substring(0, 64) : label, Math.min(16, num(o, "distance")),
                        o.has("dim") ? o.get("dim").getAsString() : null));
            } catch (RuntimeException e) {
                targets.remove(name);
            }
        });
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsDouble() : 0;
    }

    void clear() {
        targets.clear();
        hud.prompt(null);
    }

    /** Every client tick: which target is in front of the player, its prompt, and the key. */
    void tick(Minecraft mc) {
        Target focused = mc.player == null || mc.level == null || ClientCompat.screen() != null || targets.isEmpty() ? null : focused(mc);
        hud.prompt(focused == null ? null : "[" + ClientKeys.interact.getTranslatedKeyMessage().getString() + "] " + focused.label());
        while (ClientKeys.interact.consumeClick())
            if (focused != null) host.network().emit(focused.resource(), null, EVENT, "{\"id\":" + focused.id() + "}");
    }

    private Target focused(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getLookAngle();
        String dim = Compat.dimension(mc.level);
        HitResult hit = mc.hitResult;
        Target best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Target t : targets.values()) {
            double d;
            switch (t.kind()) {
                case "block" -> {
                    if (!dim.equals(t.dim()) || !(hit instanceof BlockHitResult b) || b.getType() != HitResult.Type.BLOCK) continue;
                    var p = b.getBlockPos();
                    if (p.getX() != (int) Math.floor(t.x()) || p.getY() != (int) Math.floor(t.y()) || p.getZ() != (int) Math.floor(t.z())) continue;
                    d = eye.distanceTo(b.getLocation());
                }
                case "entity" -> {
                    if (!(hit instanceof EntityHitResult e) || !e.getEntity().getStringUUID().equals(t.entity())) continue;
                    d = eye.distanceTo(e.getLocation());
                }
                case "pos" -> {
                    if (!dim.equals(t.dim())) continue;
                    Vec3 to = new Vec3(t.x(), t.y(), t.z()).subtract(eye);
                    double along = to.dot(look);
                    if (along <= 0 || to.lengthSqr() - along * along > t.radius() * t.radius()) continue; // the look ray misses its sphere
                    d = along;
                }
                default -> {
                    continue;
                }
            }
            if (d <= t.distance() && d < bestDistance) {
                best = t;
                bestDistance = d;
            }
        }
        return best;
    }
}

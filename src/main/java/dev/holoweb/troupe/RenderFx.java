package dev.holoweb.troupe;

import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Camera and screen effects scripts can ask for (the mixins read this state):
 * <pre>
 * render.post(id | nil)            a post effect from the assets ('ns:name': post_effect/name.json, or shaders/post/name.json
 *                                  before 1.21.2); nil turns it off. One at a time, last call wins.
 * render.fov(multiplier | nil)     0.1..3 times the player's FOV
 * render.shake(intensity, seconds) the view shakes, fading out (intensity in degrees, up to 10)
 * render.camera({ x, y, z, yaw, pitch } | nil)   a fixed camera (cutscenes); nil gives it back to the player
 * </pre>
 * Whatever a resource set goes away when it stops or the player leaves the server. Render thread.
 */
public final class RenderFx {
    public static volatile Identifier post;
    private static String postOwner, fovOwner, cameraOwner;
    public static volatile float fov = 1;
    private static float shakeIntensity;
    private static long shakeStart, shakeEnd;
    /** x, y, z, yaw, pitch of the fixed camera, or null. */
    public static volatile double[] camera;

    private RenderFx() {}

    static void register(ModuleRegistry modules, ResourceHost host) {
        modules.global("render")
                .function("post", a -> {
                    post = a.get(0) == null ? null : ServerApi.id(a.string(0));
                    postOwner = a.resource();
                    return null;
                })
                .function("fov", a -> {
                    fov = a.get(0) == null ? 1 : (float) Math.max(0.1, Math.min(3, a.number(0)));
                    fovOwner = a.resource();
                    return null;
                })
                .function("shake", a -> {
                    double seconds = a.optNumber(1, 0.5);
                    if (!(seconds > 0) || seconds > 60) throw new IllegalArgumentException("seconds must be 0..60");
                    shakeIntensity = (float) Math.max(0, Math.min(10, a.number(0)));
                    shakeStart = System.nanoTime();
                    shakeEnd = shakeStart + (long) (seconds * 1e9);
                    return null;
                })
                .function("camera", a -> {
                    if (a.get(0) == null) {
                        camera = null;
                        return null;
                    }
                    Map<String, Object> o = a.table(0);
                    camera = new double[]{num(o, "x"), num(o, "y"), num(o, "z"), opt(o, "yaw"), opt(o, "pitch")};
                    cameraOwner = a.resource();
                    return null;
                });
        host.onStop(resource -> {
            if (resource.equals(postOwner)) post = null;
            if (resource.equals(fovOwner)) fov = 1;
            if (resource.equals(cameraOwner)) camera = null;
        });
    }

    /** Leaving a server. */
    static void reset() {
        post = null;
        fov = 1;
        camera = null;
        shakeEnd = 0;
    }

    /** Degrees to add to {yaw, pitch} for the shake right now (fading out), or null when not shaking. */
    public static float[] shake() {
        long now = System.nanoTime();
        if (now >= shakeEnd) return null;
        float left = (float) (shakeEnd - now) / (shakeEnd - shakeStart);
        float s = shakeIntensity * left;
        var r = ThreadLocalRandom.current();
        return new float[]{(r.nextFloat() * 2 - 1) * s, (r.nextFloat() * 2 - 1) * s};
    }

    private static double num(Map<String, Object> o, String k) {
        if (!(o.get(k) instanceof Number n)) throw new IllegalArgumentException(k + " must be a number");
        return n.doubleValue();
    }

    private static double opt(Map<String, Object> o, String k) {
        return o.get(k) instanceof Number n ? n.doubleValue() : 0;
    }
}

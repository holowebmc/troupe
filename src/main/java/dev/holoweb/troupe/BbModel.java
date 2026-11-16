package dev.holoweb.troupe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.holoweb.troupe.lua.LuaRuntime;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Blockbench project ({@code models/<name>.bbmodel} in a resource): cubes in a tree of bones, the textures embedded
 * in it, and its keyframe animations. Plain Java (Gson and JOML only), so the server checks names with the same parser.
 * <p>
 * Coordinates are Blockbench's own (pixels, the model facing north); {@link #mesh} turns them into blocks. Bone and cube
 * rotations are Euler degrees applied Z, Y, X (Blockbench's order); in animations X and Y rotation and X position are
 * negated, as Blockbench itself shows them. Mesh elements, locators and box-UV cubes saved without face UVs are
 * skipped. Bezier keyframes play as linear, and Molang expressions as 0: only numbers are read.
 */
final class BbModel {
    enum Loop { LOOP, ONCE, HOLD }

    enum Interp { LINEAR, CATMULLROM, STEP }

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    private static final float[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    static final int MAX_BYTES = 16 << 20, MAX_CUBES = 4096;

    record Face(float u0, float v0, float u1, float v1, int texture, int rotation) {}

    record Cube(Vector3f from, Vector3f to, Vector3f origin, Vector3f rotation, Face[] faces) {}

    static final class Bone {
        final String uuid;
        final Vector3f origin, rotation;
        final List<Bone> bones = new ArrayList<>();
        final List<Cube> cubes = new ArrayList<>();

        Bone(String uuid, Vector3f origin, Vector3f rotation) {
            this.uuid = uuid;
            this.origin = origin;
            this.rotation = rotation;
        }
    }

    record Texture(byte[] png, float uvWidth, float uvHeight) {}

    /** {@code pre} is the value arriving at the keyframe, {@code post} the one leaving it (equal unless split). */
    record Key(float time, Vector3f pre, Vector3f post, Interp interp) {}

    record Channels(Key[] rotation, Key[] position, Key[] scale) {}

    record Animation(String name, Loop loop, float length, Map<String, Channels> bones) {
        /** The time to pose at {@code seconds} after the start; -1 when a {@code once} animation is over. */
        float at(double seconds) {
            if (length <= 0) return 0;
            return switch (loop) {
                case LOOP -> (float) (seconds % length);
                case HOLD -> (float) Math.min(seconds, length);
                case ONCE -> seconds >= length ? -1 : (float) seconds;
            };
        }
    }

    /** One face as 4 corners (x, y, z, u, v each, u/v in 0..1) and its normal, already posed. */
    interface QuadSink {
        void quad(int texture, float[] corners, float nx, float ny, float nz);
    }

    final List<Bone> roots;
    final List<Texture> textures;
    final Map<String, Animation> animations;
    private final Vector3f offset;

    private BbModel(List<Bone> roots, List<Texture> textures, Map<String, Animation> animations, Vector3f offset) {
        this.roots = roots;
        this.textures = textures;
        this.animations = animations;
        this.offset = offset;
    }

    // ---- loading

    private static final Map<LuaRuntime.Files, Map<String, BbModel>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The model {@code models/<name>.bbmodel} of a resource, parsed once per version of its files (a restart with
     * changed files reads it again). Throws with a message for scripts when it's missing or broken.
     */
    static BbModel load(LuaRuntime.Files files, String resource, String name) {
        if (!name.matches("[A-Za-z0-9_\\-/]{1,64}") || name.contains("//") || name.startsWith("/"))
            throw new IllegalArgumentException("bad model name '" + name + "'");
        if (files == null) throw new IllegalArgumentException("no resource '" + resource + "'");
        Map<String, BbModel> byName = CACHE.computeIfAbsent(files, f -> Collections.synchronizedMap(new HashMap<>()));
        BbModel cached = byName.get(name);
        if (cached != null) return cached;
        byte[] bytes;
        try {
            bytes = files.read("models/" + name + ".bbmodel");
        } catch (IOException e) {
            bytes = null;
        }
        if (bytes == null) throw new IllegalArgumentException("no model '" + name + "' in " + resource + " (models/" + name + ".bbmodel)");
        BbModel m;
        try {
            m = parse(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("model '" + resource + ":" + name + "' can't be read: " + e.getMessage());
        }
        byName.put(name, m);
        return m;
    }

    /** {@code name} as {@code resource:name}: a name without a resource is the caller's own. */
    static String qualify(String resource, String name) {
        return name.indexOf(':') < 0 ? resource + ":" + name : name;
    }

    /** A qualified model ({@code resource:name}) from the resources this host has. */
    static BbModel load(dev.holoweb.troupe.lua.ResourceHost host, String ref) {
        int colon = ref.indexOf(':');
        return load(host.filesOf(ref.substring(0, colon)), ref.substring(0, colon), ref.substring(colon + 1));
    }

    Animation animation(String name, String ref) {
        Animation a = animations.get(name);
        if (a == null) throw new IllegalArgumentException("model '" + ref + "' has no animation '" + name + "'"
                + (animations.isEmpty() ? "" : " (it has " + String.join(", ", animations.keySet()) + ")"));
        return a;
    }

    static BbModel parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("over " + (MAX_BYTES >> 20) + " MB");
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject meta = obj(root, "meta");
        boolean block = meta != null && "java_block".equals(str(meta, "model_format"));
        JsonObject res = obj(root, "resolution");
        float resW = res == null ? 16 : num(res, "width", 16), resH = res == null ? 16 : num(res, "height", 16);

        List<Texture> textures = new ArrayList<>();
        for (JsonElement t : arr(root, "textures")) {
            JsonObject o = t.getAsJsonObject();
            String src = str(o, "source");
            if (src == null || !src.startsWith("data:image/png;base64,"))
                throw new IllegalArgumentException("texture '" + str(o, "name") + "' isn't embedded as PNG (save the project with its textures)");
            textures.add(new Texture(Base64.getDecoder().decode(src.substring(src.indexOf(',') + 1)),
                    num(o, "uv_width", resW), num(o, "uv_height", resH)));
        }

        Map<String, JsonObject> elements = new HashMap<>();
        for (JsonElement e : arr(root, "elements")) {
            JsonObject o = e.getAsJsonObject();
            elements.put(str(o, "uuid"), o);
        }
        Map<String, JsonObject> groups = new HashMap<>(); // the outliner names groups by uuid from format 5 on
        for (JsonElement g : arr(root, "groups")) {
            JsonObject o = g.getAsJsonObject();
            groups.put(str(o, "uuid"), o);
        }
        Bone top = new Bone(null, new Vector3f(), new Vector3f());
        int[] cubes = {0};
        for (JsonElement node : arr(root, "outliner")) add(top, node, elements, groups, cubes);
        List<Bone> roots = new ArrayList<>(top.bones);
        if (!top.cubes.isEmpty()) roots.add(top);

        Map<String, Animation> animations = new LinkedHashMap<>();
        for (JsonElement a : arr(root, "animations")) {
            Animation anim = animation(a.getAsJsonObject());
            animations.put(anim.name(), anim);
        }
        return new BbModel(roots, textures, animations, block ? new Vector3f(-8, 0, -8) : new Vector3f());
    }

    private static void add(Bone parent, JsonElement node, Map<String, JsonObject> elements, Map<String, JsonObject> groups, int[] cubes) {
        if (node.isJsonPrimitive()) {
            JsonObject e = elements.get(node.getAsString());
            if (e == null || !"cube".equals(str(e, "type") == null ? "cube" : str(e, "type")) || !bool(e, "visibility") || !bool(e, "export")) return;
            if (++cubes[0] > MAX_CUBES) throw new IllegalArgumentException("over " + MAX_CUBES + " cubes");
            parent.cubes.add(cube(e));
            return;
        }
        JsonObject o = node.getAsJsonObject();
        JsonObject g = o.has("name") ? o : groups.get(str(o, "uuid"));
        if (g == null || !bool(g, "visibility") || !bool(g, "export")) return;
        Bone bone = new Bone(str(g, "uuid"), vec(g, "origin"), vec(g, "rotation"));
        parent.bones.add(bone);
        for (JsonElement child : arr(o, "children")) add(bone, child, elements, groups, cubes);
    }

    private static Cube cube(JsonObject e) {
        Vector3f from = vec(e, "from"), to = vec(e, "to");
        float inflate = num(e, "inflate", 0);
        from.sub(inflate, inflate, inflate);
        to.add(inflate, inflate, inflate);
        Face[] faces = new Face[6];
        JsonObject f = obj(e, "faces");
        for (int i = 0; i < 6 && f != null; i++) {
            JsonObject face = obj(f, FACES[i]);
            if (face == null || !face.has("uv") || !face.has("texture") || face.get("texture").isJsonNull()) continue;
            JsonArray uv = face.getAsJsonArray("uv");
            faces[i] = new Face(uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat(),
                    face.get("texture").getAsInt(), (int) num(face, "rotation", 0));
        }
        return new Cube(from, to, vec(e, "origin"), vec(e, "rotation"), faces);
    }

    private static Animation animation(JsonObject a) {
        String name = str(a, "name");
        Loop loop = switch (String.valueOf(str(a, "loop"))) {
            case "loop" -> Loop.LOOP;
            case "hold" -> Loop.HOLD;
            default -> Loop.ONCE;
        };
        float length = num(a, "length", 0), last = 0;
        Map<String, Channels> bones = new HashMap<>();
        JsonObject animators = obj(a, "animators");
        if (animators != null) for (Map.Entry<String, JsonElement> en : animators.entrySet()) {
            JsonObject an = en.getValue().getAsJsonObject();
            if (an.has("type") && !"bone".equals(str(an, "type"))) continue;
            Map<String, List<Key>> by = new HashMap<>();
            for (JsonElement k : arr(an, "keyframes")) {
                JsonObject kf = k.getAsJsonObject();
                JsonArray points = kf.getAsJsonArray("data_points");
                if (points == null || points.isEmpty()) continue;
                float time = num(kf, "time", 0);
                last = Math.max(last, time);
                Interp interp = switch (String.valueOf(str(kf, "interpolation"))) {
                    case "catmullrom" -> Interp.CATMULLROM;
                    case "step" -> Interp.STEP;
                    default -> Interp.LINEAR;
                };
                by.computeIfAbsent(String.valueOf(str(kf, "channel")), c -> new ArrayList<>())
                        .add(new Key(time, point(points.get(0)), point(points.get(points.size() - 1)), interp));
            }
            bones.put(en.getKey(), new Channels(keys(by.get("rotation")), keys(by.get("position")), keys(by.get("scale"))));
        }
        if (name == null) throw new IllegalArgumentException("an animation has no name");
        return new Animation(name, loop, length > 0 ? length : last, bones);
    }

    private static Key[] keys(List<Key> list) {
        if (list == null) return null;
        list.sort(Comparator.comparingDouble(Key::time));
        return list.toArray(Key[]::new);
    }

    private static Vector3f point(JsonElement p) {
        JsonObject o = p.getAsJsonObject();
        return new Vector3f(value(o.get("x")), value(o.get("y")), value(o.get("z")));
    }

    /** A number, or a string holding one; anything else (Molang) is 0. */
    private static float value(JsonElement v) {
        if (v == null || v.isJsonNull()) return 0;
        try {
            return v.getAsJsonPrimitive().isNumber() ? v.getAsFloat() : Float.parseFloat(v.getAsString().trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- posing

    /** Poses the model ({@code animation} at {@code time} seconds, or at rest when null) under {@code root}, in blocks. */
    void mesh(Animation animation, float time, Matrix4f root, QuadSink sink) {
        Matrix4f m = new Matrix4f(root).scale(1 / 16f).translate(offset);
        for (Bone b : roots) bone(b, m, animation, time, sink);
    }

    private void bone(Bone b, Matrix4f parent, Animation anim, float time, QuadSink sink) {
        Matrix4f m = new Matrix4f(parent).translate(b.origin);
        Vector3f rot = new Vector3f(b.rotation);
        Channels ch = anim == null || b.uuid == null ? null : anim.bones().get(b.uuid);
        if (ch != null) {
            Vector3f p = sample(ch.position(), time);
            if (p != null) m.translate(-p.x, p.y, p.z);
            Vector3f r = sample(ch.rotation(), time);
            if (r != null) rot.add(-r.x, -r.y, r.z);
        }
        rotate(m, rot);
        if (ch != null) {
            Vector3f s = sample(ch.scale(), time);
            if (s != null) m.scale(s);
        }
        m.translate(-b.origin.x, -b.origin.y, -b.origin.z);
        for (Cube c : b.cubes) cube(c, m, sink);
        for (Bone child : b.bones) bone(child, m, anim, time, sink);
    }

    private static void rotate(Matrix4f m, Vector3f deg) {
        m.rotateZ((float) Math.toRadians(deg.z)).rotateY((float) Math.toRadians(deg.y)).rotateX((float) Math.toRadians(deg.x));
    }

    private void cube(Cube c, Matrix4f bone, QuadSink sink) {
        Matrix4f m = new Matrix4f(bone).translate(c.origin());
        rotate(m, c.rotation());
        m.translate(-c.origin().x, -c.origin().y, -c.origin().z);
        float x0 = c.from().x, y0 = c.from().y, z0 = c.from().z, x1 = c.to().x, y1 = c.to().y, z1 = c.to().z;
        // corners top-left, bottom-left, bottom-right, top-right as seen from outside (Minecraft's face UV order)
        float[][][] corners = {
                {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}},
                {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}},
                {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}},
                {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}},
                {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}},
                {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}}};
        Vector3f v = new Vector3f();
        for (int i = 0; i < 6; i++) {
            Face f = c.faces()[i];
            if (f == null || f.texture() < 0 || f.texture() >= textures.size()) continue;
            Texture t = textures.get(f.texture());
            float[][] uv = {{f.u0(), f.v0()}, {f.u0(), f.v1()}, {f.u1(), f.v1()}, {f.u1(), f.v0()}};
            int shift = Math.floorMod(f.rotation() / 90, 4);
            float[] out = new float[20];
            for (int k = 0; k < 4; k++) {
                m.transformPosition(v.set(corners[i][k][0], corners[i][k][1], corners[i][k][2]));
                float[] tex = uv[(k + shift) % 4];
                out[k * 5] = v.x;
                out[k * 5 + 1] = v.y;
                out[k * 5 + 2] = v.z;
                out[k * 5 + 3] = tex[0] / t.uvWidth();
                out[k * 5 + 4] = tex[1] / t.uvHeight();
            }
            m.transformDirection(v.set(NORMALS[i][0], NORMALS[i][1], NORMALS[i][2]));
            if (v.lengthSquared() > 0) v.normalize();
            sink.quad(f.texture(), out, v.x, v.y, v.z);
        }
    }

    /** A channel's value at {@code t}, or null when it has no keyframes. */
    static Vector3f sample(Key[] keys, float t) {
        if (keys == null || keys.length == 0) return null;
        if (t <= keys[0].time()) return new Vector3f(keys[0].pre());
        Key last = keys[keys.length - 1];
        if (t >= last.time()) return new Vector3f(last.post());
        int i = 0;
        while (keys[i + 1].time() <= t) i++;
        Key a = keys[i], b = keys[i + 1];
        float f = b.time() == a.time() ? 1 : (t - a.time()) / (b.time() - a.time());
        if (a.interp() == Interp.STEP) return new Vector3f(a.post());
        if (a.interp() == Interp.CATMULLROM || b.interp() == Interp.CATMULLROM) {
            Vector3f p0 = i > 0 ? keys[i - 1].post() : a.post(), p3 = i + 2 < keys.length ? keys[i + 2].pre() : b.pre();
            return new Vector3f(catmull(p0.x, a.post().x, b.pre().x, p3.x, f), catmull(p0.y, a.post().y, b.pre().y, p3.y, f),
                    catmull(p0.z, a.post().z, b.pre().z, p3.z, f));
        }
        return new Vector3f(a.post()).lerp(b.pre(), f);
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (3 * p1 - p0 - 3 * p2 + p3) * t * t * t);
    }

    // ---- json

    private static JsonObject obj(JsonObject o, String key) {
        return o.get(key) instanceof JsonObject j ? j : null;
    }

    private static Iterable<JsonElement> arr(JsonObject o, String key) {
        return o.get(key) instanceof JsonArray a ? a : List.of();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static float num(JsonObject o, String key, float fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : fallback;
    }

    /** Absent means true (visibility, export). */
    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() || e.getAsBoolean();
    }

    private static Vector3f vec(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonArray a) || a.size() < 3) return new Vector3f();
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}

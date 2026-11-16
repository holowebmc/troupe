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
 * A Blockbench project ({@code models/<name>.bbmodel} in a resource): cubes and meshes in a tree of bones, the
 * textures embedded in it, and its keyframe animations. Plain Java (Gson and JOML only), so the server checks names
 * with the same parser.
 * <p>
 * Coordinates are Blockbench's own (pixels, the model facing north); {@link #mesh} turns them into blocks. Bone and
 * element rotations are Euler degrees applied Z, Y, X (Blockbench's order); in animations X and Y rotation and X
 * position are negated, as Blockbench itself shows them. Faces are worked out once when the file is read (UV
 * rotation, box UV, mesh quads), so posing only multiplies matrices. Keyframe values are {@link Molang}.
 * Locators, sound and particle keyframes are skipped.
 */
final class BbModel {
    enum Loop { LOOP, ONCE, HOLD }

    enum Interp { LINEAR, CATMULLROM, BEZIER, STEP }

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
    private static final float[][] NORMALS = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    static final int MAX_BYTES = 16 << 20, MAX_FACES = 24576;

    /** One face in its part's space: 4 corners (x, y, z in pixels), their UVs (0..1) and its normal. */
    record Face(float[] pos, float[] uv, float nx, float ny, float nz, int texture) {}

    /** A cube or a mesh: its faces, and the transform from its own space to its bone's. */
    record Part(Matrix4f local, Face[] faces) {}

    static final class Bone {
        final String uuid;
        final Vector3f origin, rotation;
        final List<Bone> bones = new ArrayList<>();
        final List<Part> parts = new ArrayList<>();

        Bone(String uuid, Vector3f origin, Vector3f rotation) {
            this.uuid = uuid;
            this.origin = origin;
            this.rotation = rotation;
        }
    }

    record Texture(byte[] png, float uvWidth, float uvHeight) {}

    /**
     * {@code pre} is the value arriving at the keyframe, {@code post} the one leaving it (equal unless split).
     * {@code bezier}: left time, left value, right time, right value (3 each), offsets from the keyframe.
     */
    record Key(float time, Molang.Expr[] pre, Molang.Expr[] post, Interp interp, float[] bezier) {}

    record Channels(Key[] rotation, Key[] position, Key[] scale) {}

    record Animation(String name, Loop loop, float length, Map<String, Channels> bones) {
        /** The time to pose at {@code seconds} after the start; -1 when a {@code once} animation is over. */
        float at(double seconds) {
            if (length <= 0) return 0;
            if (seconds < 0) seconds = 0;
            return switch (loop) {
                case LOOP -> (float) (seconds % length);
                case HOLD -> (float) Math.min(seconds, length);
                case ONCE -> seconds >= length ? -1 : (float) seconds;
            };
        }
    }

    /** Receives the posed model: 4 vertices per face, in blocks, with the face's normal. */
    interface VertexSink {
        void vertex(int texture, float x, float y, float z, float u, float v, float nx, float ny, float nz);
    }

    final List<Bone> roots;
    final List<Texture> textures;
    final Map<String, Animation> animations;
    /** At rest, in blocks from the model's origin: the farthest corner, the widest one sideways, the highest. */
    final float radius, width, top;
    private final Vector3f offset;
    // scratch for mesh(): posing happens on one thread (the client's render thread)
    private final Matrix4f[] stack;
    private final Matrix4f part = new Matrix4f();
    private final Vector3f tmp = new Vector3f(), rot = new Vector3f(), normal = new Vector3f();

    private BbModel(List<Bone> roots, List<Texture> textures, Map<String, Animation> animations, Vector3f offset, int depth) {
        this.roots = roots;
        this.textures = textures;
        this.animations = animations;
        this.offset = offset;
        this.stack = new Matrix4f[depth + 2];
        for (int i = 0; i < stack.length; i++) stack[i] = new Matrix4f();
        float[] b = new float[3];
        mesh(null, 0, new Matrix4f(), (texture, x, y, z, u, v, nx, ny, nz) -> {
            b[0] = Math.max(b[0], x * x + y * y + z * z);
            b[1] = Math.max(b[1], Math.max(Math.abs(x), Math.abs(z)));
            b[2] = Math.max(b[2], y);
        });
        radius = (float) Math.sqrt(b[0]);
        width = b[1];
        top = b[2];
    }

    // ---- loading

    private static final Map<LuaRuntime.Files, Map<String, BbModel>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code name} as {@code resource:name}: a name without a resource is the caller's own. */
    static String qualify(String resource, String name) {
        return name.indexOf(':') < 0 ? resource + ":" + name : name;
    }

    /** A qualified model ({@code resource:name}) from the resources this host has. */
    static BbModel load(dev.holoweb.troupe.lua.ResourceHost host, String ref) {
        int colon = ref.indexOf(':');
        return load(host.filesOf(ref.substring(0, colon)), ref.substring(0, colon), ref.substring(colon + 1));
    }

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

    Animation animation(String name, String ref) {
        Animation a = animations.get(name);
        if (a == null) throw new IllegalArgumentException("model '" + ref + "' has no animation '" + name + "'"
                + (animations.isEmpty() ? "" : " (it has " + String.join(", ", animations.keySet()) + ")"));
        return a;
    }

    /** What reading needs besides the JSON: textures for UV sizes, box UV by default, and a face budget. */
    private record Ctx(List<Texture> textures, boolean boxUv, int[] faces) {}

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
        Map<String, JsonObject> groups = new HashMap<>(); // the outliner names groups by uuid from Blockbench 5 on
        for (JsonElement g : arr(root, "groups")) {
            JsonObject o = g.getAsJsonObject();
            groups.put(str(o, "uuid"), o);
        }
        Ctx ctx = new Ctx(textures, meta != null && meta.has("box_uv") && meta.get("box_uv").getAsBoolean(), new int[1]);
        Bone top = new Bone(null, new Vector3f(), new Vector3f());
        int depth = 0;
        for (JsonElement node : arr(root, "outliner")) depth = Math.max(depth, add(top, node, elements, groups, ctx, 1));
        List<Bone> roots = new ArrayList<>(top.bones);
        if (!top.parts.isEmpty()) roots.add(top);

        Map<String, Animation> animations = new LinkedHashMap<>();
        for (JsonElement a : arr(root, "animations")) {
            Animation anim = animation(a.getAsJsonObject());
            animations.put(anim.name(), anim);
        }
        return new BbModel(roots, textures, animations, block ? new Vector3f(-8, 0, -8) : new Vector3f(), depth);
    }

    /** Adds an outliner node under {@code parent}; returns how deep the bones under it go. */
    private static int add(Bone parent, JsonElement node, Map<String, JsonObject> elements, Map<String, JsonObject> groups, Ctx ctx, int depth) {
        if (node.isJsonPrimitive()) {
            JsonObject e = elements.get(node.getAsString());
            if (e == null || !bool(e, "visibility") || !bool(e, "export")) return 0;
            String type = str(e, "type") == null ? "cube" : str(e, "type");
            Part p = type.equals("cube") ? cube(e, ctx) : type.equals("mesh") ? mesh(e, ctx) : null;
            if (p != null && p.faces().length > 0) parent.parts.add(p);
            if ((ctx.faces()[0] += p == null ? 0 : p.faces().length) > MAX_FACES) throw new IllegalArgumentException("over " + MAX_FACES + " faces");
            return 0;
        }
        JsonObject o = node.getAsJsonObject();
        JsonObject g = o.has("name") ? o : groups.get(str(o, "uuid"));
        if (g == null || !bool(g, "visibility") || !bool(g, "export")) return 0;
        if (depth > 64) throw new IllegalArgumentException("groups nested over 64 deep");
        Bone bone = new Bone(str(g, "uuid"), vec(g, "origin"), vec(g, "rotation"));
        parent.bones.add(bone);
        int deepest = depth;
        for (JsonElement child : arr(o, "children")) deepest = Math.max(deepest, add(bone, child, elements, groups, ctx, depth + 1));
        return deepest;
    }

    private static Matrix4f rotation(Matrix4f m, Vector3f deg) {
        return m.rotateZ((float) Math.toRadians(deg.z)).rotateY((float) Math.toRadians(deg.y)).rotateX((float) Math.toRadians(deg.x));
    }

    private static Part cube(JsonObject e, Ctx ctx) {
        Vector3f from = vec(e, "from"), to = vec(e, "to"), o = vec(e, "origin");
        float w = to.x - from.x, h = to.y - from.y, d = to.z - from.z, inflate = num(e, "inflate", 0);
        float x0 = from.x - inflate, y0 = from.y - inflate, z0 = from.z - inflate, x1 = to.x + inflate, y1 = to.y + inflate, z1 = to.z + inflate;
        // corners top-left, bottom-left, bottom-right, top-right as seen from outside (Minecraft's face UV order)
        float[][] corners = {
                {x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0},
                {x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1},
                {x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0},
                {x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1},
                {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
                {x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1}};
        boolean boxUv = e.has("box_uv") ? e.get("box_uv").getAsBoolean() : ctx.boxUv();
        float[][] box = null;
        if (boxUv && e.get("uv_offset") instanceof JsonArray off && off.size() >= 2) {
            float u = off.get(0).getAsFloat(), v = off.get(1).getAsFloat();
            box = new float[][]{ // Blockbench's box UV layout: east, north, west, south in a row, up and down above
                    {u + d, v + d, u + d + w, v + d + h},
                    {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h},
                    {u, v + d, u + d, v + d + h},
                    {u + d + w, v + d, u + 2 * d + w, v + d + h},
                    {u + d + w, v + d, u + d, v},
                    {u + d + 2 * w, v, u + d + w, v + d}};
            if (e.has("mirror_uv") && e.get("mirror_uv").getAsBoolean()) {
                for (float[] f : box) {
                    float s = f[0];
                    f[0] = f[2];
                    f[2] = s;
                }
                float[] east = box[2];
                box[2] = box[3];
                box[3] = east;
            }
        }
        List<Face> faces = new ArrayList<>();
        JsonObject f = obj(e, "faces");
        for (int i = 0; i < 6 && f != null; i++) {
            JsonObject face = obj(f, FACES[i]);
            if (face == null || !face.has("texture") || face.get("texture").isJsonNull()) continue;
            int texture = face.get("texture").getAsInt();
            if (texture < 0 || texture >= ctx.textures().size()) continue;
            float[] r;
            if (face.get("uv") instanceof JsonArray uv && uv.size() >= 4) r = new float[]{uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()};
            else if (box != null) r = box[i];
            else continue;
            Texture t = ctx.textures().get(texture);
            float[][] uv = {{r[0], r[1]}, {r[0], r[3]}, {r[2], r[3]}, {r[2], r[1]}};
            int shift = Math.floorMod((int) num(face, "rotation", 0) / 90, 4);
            float[] out = new float[8];
            for (int k = 0; k < 4; k++) {
                out[k * 2] = uv[(k + shift) % 4][0] / t.uvWidth();
                out[k * 2 + 1] = uv[(k + shift) % 4][1] / t.uvHeight();
            }
            faces.add(new Face(corners[i], out, NORMALS[i][0], NORMALS[i][1], NORMALS[i][2], texture));
        }
        Matrix4f local = rotation(new Matrix4f().translate(o), vec(e, "rotation")).translate(-o.x, -o.y, -o.z);
        return new Part(local, faces.toArray(Face[]::new));
    }

    /** A mesh element: vertices around its origin, faces of 3 or 4 of them with a UV per vertex. */
    private static Part mesh(JsonObject e, Ctx ctx) {
        JsonObject vertices = obj(e, "vertices"), fs = obj(e, "faces");
        if (vertices == null || fs == null) return null;
        List<Face> faces = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : fs.entrySet()) {
            JsonObject face = en.getValue().getAsJsonObject();
            if (!face.has("texture") || face.get("texture").isJsonNull() || !(face.get("vertices") instanceof JsonArray keys)) continue;
            int texture = face.get("texture").getAsInt();
            JsonObject uvs = obj(face, "uv");
            if (texture < 0 || texture >= ctx.textures().size() || uvs == null || keys.size() < 3 || keys.size() > 4) continue;
            Texture t = ctx.textures().get(texture);
            float[][] p = new float[keys.size()][], q = new float[keys.size()][];
            for (int k = 0; k < keys.size(); k++) {
                String key = keys.get(k).getAsString();
                if (!(vertices.get(key) instanceof JsonArray v) || !(uvs.get(key) instanceof JsonArray uv)) {
                    p = null;
                    break;
                }
                p[k] = new float[]{v.get(0).getAsFloat(), v.get(1).getAsFloat(), v.get(2).getAsFloat()};
                q[k] = new float[]{uv.get(0).getAsFloat() / t.uvWidth(), uv.get(1).getAsFloat() / t.uvHeight()};
            }
            if (p == null) continue;
            int[] order = p.length == 3 ? new int[]{0, 1, 2, 2} : quadOrder(p);
            float[] pos = new float[12], uv = new float[8];
            for (int k = 0; k < 4; k++) {
                System.arraycopy(p[order[k]], 0, pos, k * 3, 3);
                System.arraycopy(q[order[k]], 0, uv, k * 2, 2);
            }
            Vector3f a = new Vector3f(pos[3] - pos[0], pos[4] - pos[1], pos[5] - pos[2]);
            Vector3f n = a.cross(pos[6] - pos[0], pos[7] - pos[1], pos[8] - pos[2]);
            if (n.lengthSquared() > 0) n.normalize();
            faces.add(new Face(pos, uv, n.x, n.y, n.z, texture));
        }
        Vector3f o = vec(e, "origin");
        return new Part(rotation(new Matrix4f().translate(o), vec(e, "rotation")), faces.toArray(Face[]::new));
    }

    /** A quad's corners in an order that goes around it (Blockbench doesn't store them that way): the shortest loop. */
    private static int[] quadOrder(float[][] p) {
        int[][] orders = {{0, 1, 2, 3}, {0, 1, 3, 2}, {0, 2, 1, 3}};
        int[] best = orders[0];
        double shortest = Double.MAX_VALUE;
        for (int[] o : orders) {
            double len = 0;
            for (int k = 0; k < 4; k++) {
                float[] a = p[o[k]], b = p[o[(k + 1) % 4]];
                len += Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
            }
            if (len < shortest) {
                shortest = len;
                best = o;
            }
        }
        return best;
    }

    private static Animation animation(JsonObject a) {
        String name = str(a, "name");
        if (name == null) throw new IllegalArgumentException("an animation has no name");
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
                if (!(kf.get("data_points") instanceof JsonArray points) || points.isEmpty()) continue;
                float time = num(kf, "time", 0);
                last = Math.max(last, time);
                Interp interp = switch (String.valueOf(str(kf, "interpolation"))) {
                    case "catmullrom" -> Interp.CATMULLROM;
                    case "bezier" -> Interp.BEZIER;
                    case "step" -> Interp.STEP;
                    default -> Interp.LINEAR;
                };
                float[] bezier = new float[12];
                String[] handles = {"bezier_left_time", "bezier_left_value", "bezier_right_time", "bezier_right_value"};
                float[] defaults = {-0.1f, 0, 0.1f, 0};
                for (int h = 0; h < 4; h++)
                    for (int axis = 0; axis < 3; axis++)
                        bezier[h * 3 + axis] = kf.get(handles[h]) instanceof JsonArray arr && arr.size() > axis ? arr.get(axis).getAsFloat() : defaults[h];
                by.computeIfAbsent(String.valueOf(str(kf, "channel")), c -> new ArrayList<>())
                        .add(new Key(time, point(points.get(0)), point(points.get(points.size() - 1)), interp, bezier));
            }
            bones.put(en.getKey(), new Channels(keys(by.get("rotation")), keys(by.get("position")), keys(by.get("scale"))));
        }
        return new Animation(name, loop, length > 0 ? length : last, bones);
    }

    private static Key[] keys(List<Key> list) {
        if (list == null) return null;
        list.sort(Comparator.comparingDouble(Key::time));
        return list.toArray(Key[]::new);
    }

    private static Molang.Expr[] point(JsonElement p) {
        JsonObject o = p.getAsJsonObject();
        return new Molang.Expr[]{value(o.get("x")), value(o.get("y")), value(o.get("z"))};
    }

    private static Molang.Expr value(JsonElement v) {
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) return Molang.ZERO;
        if (v.getAsJsonPrimitive().isNumber()) {
            float f = v.getAsFloat();
            return t -> f;
        }
        return Molang.parse(v.getAsString());
    }

    // ---- posing

    /**
     * Poses the model ({@code animation} at {@code time} seconds, or at rest when null) under {@code root}, in blocks.
     * Allocates nothing; not thread-safe (it reuses scratch matrices).
     */
    void mesh(Animation animation, float time, Matrix4f root, VertexSink sink) {
        stack[0].set(root).scale(1 / 16f).translate(offset);
        for (Bone b : roots) bone(b, 1, animation, time, sink);
    }

    private void bone(Bone b, int depth, Animation anim, float time, VertexSink sink) {
        Matrix4f m = stack[depth].set(stack[depth - 1]).translate(b.origin);
        rot.set(b.rotation);
        Channels ch = anim == null || b.uuid == null ? null : anim.bones().get(b.uuid);
        if (ch != null) {
            if (sample(ch.position(), time, tmp)) m.translate(-tmp.x, tmp.y, tmp.z);
            if (sample(ch.rotation(), time, tmp)) rot.add(-tmp.x, -tmp.y, tmp.z);
        }
        rotation(m, rot);
        if (ch != null && sample(ch.scale(), time, tmp)) m.scale(tmp);
        m.translate(-b.origin.x, -b.origin.y, -b.origin.z);
        for (Part p : b.parts) {
            part.set(m).mul(p.local());
            for (Face f : p.faces()) {
                part.transformDirection(normal.set(f.nx(), f.ny(), f.nz()));
                if (normal.lengthSquared() > 0) normal.normalize();
                float[] pos = f.pos(), uv = f.uv();
                for (int k = 0; k < 4; k++) {
                    part.transformPosition(tmp.set(pos[k * 3], pos[k * 3 + 1], pos[k * 3 + 2]));
                    sink.vertex(f.texture(), tmp.x, tmp.y, tmp.z, uv[k * 2], uv[k * 2 + 1], normal.x, normal.y, normal.z);
                }
            }
        }
        for (Bone child : b.bones) bone(child, depth + 1, anim, time, sink);
    }

    /** A channel's value at {@code t} into {@code out}; false (and {@code out} untouched) when it has no keyframes. */
    static boolean sample(Key[] keys, float t, Vector3f out) {
        if (keys == null || keys.length == 0) return false;
        if (t <= keys[0].time()) return eval(keys[0].pre(), t, out);
        Key last = keys[keys.length - 1];
        if (t >= last.time()) return eval(last.post(), t, out);
        int i = 0;
        while (keys[i + 1].time() <= t) i++;
        Key a = keys[i], b = keys[i + 1];
        if (a.interp() == Interp.STEP) return eval(a.post(), t, out);
        float f = (t - a.time()) / (b.time() - a.time());
        for (int axis = 0; axis < 3; axis++) {
            float va = a.post()[axis].eval(t), vb = b.pre()[axis].eval(t);
            if (a.interp() == Interp.CATMULLROM || b.interp() == Interp.CATMULLROM) {
                float p0 = i > 0 ? keys[i - 1].post()[axis].eval(t) : va, p3 = i + 2 < keys.length ? keys[i + 2].pre()[axis].eval(t) : vb;
                out.setComponent(axis, catmull(p0, va, vb, p3, f));
            } else if (a.interp() == Interp.BEZIER || b.interp() == Interp.BEZIER) {
                float rt = a.interp() == Interp.BEZIER ? a.bezier()[6 + axis] : 0, rv = a.interp() == Interp.BEZIER ? a.bezier()[9 + axis] : 0;
                float lt = b.interp() == Interp.BEZIER ? b.bezier()[axis] : 0, lv = b.interp() == Interp.BEZIER ? b.bezier()[3 + axis] : 0;
                out.setComponent(axis, bezier(a.time(), va, a.time() + rt, va + rv, b.time() + lt, vb + lv, b.time(), vb, t));
            } else {
                out.setComponent(axis, va + (vb - va) * f);
            }
        }
        return true;
    }

    private static boolean eval(Molang.Expr[] e, float t, Vector3f out) {
        out.set(e[0].eval(t), e[1].eval(t), e[2].eval(t));
        return true;
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (3 * p1 - p0 - 3 * p2 + p3) * t * t * t);
    }

    /** The value of a cubic Bézier through (time, value) points at time {@code t}: the curve's time is solved for. */
    private static float bezier(float t0, float v0, float t1, float v1, float t2, float v2, float t3, float v3, float t) {
        t1 = Math.max(t0, Math.min(t3, t1)); // handles past the neighbouring keyframe would fold the curve back
        t2 = Math.max(t0, Math.min(t3, t2));
        float lo = 0, hi = 1, s = 0.5f;
        for (int i = 0; i < 24; i++) {
            s = (lo + hi) / 2;
            if (cubic(t0, t1, t2, t3, s) < t) lo = s;
            else hi = s;
        }
        return cubic(v0, v1, v2, v3, s);
    }

    private static float cubic(float a, float b, float c, float d, float s) {
        float r = 1 - s;
        return r * r * r * a + 3 * r * r * s * b + 3 * r * s * s * c + s * s * s * d;
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

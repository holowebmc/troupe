package dev.holoweb.troupe;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BbModelTest {
    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

    /** One bone at (0, 0, 0) holding an 8×8×8 cube on the ground, and a 'spin' animation turning it 90° in 1 s. */
    private static String model(String outliner, String groups, String extraElements) {
        return """
                {"meta": {"format_version": "4.10", "model_format": "free"},
                 "resolution": {"width": 32, "height": 32},
                 "textures": [{"name": "skin", "source": "%s", "uv_width": 32, "uv_height": 32}],
                 "elements": [
                   {"uuid": "c1", "type": "cube", "from": [-4, 0, -4], "to": [4, 8, 4], "origin": [0, 0, 0],
                    "faces": {"north": {"uv": [0, 0, 8, 8], "texture": 0}, "south": {"uv": [8, 0, 16, 8], "texture": 0},
                              "east": {"uv": [0, 8, 8, 16], "texture": 0}, "west": {"uv": [8, 8, 16, 16], "texture": 0},
                              "up": {"uv": [16, 0, 24, 8], "texture": 0}, "down": {"uv": [16, 8, 24, 16], "texture": null}}},
                   {"uuid": "l1", "type": "locator"}%s],
                 "outliner": %s, %s
                 "animations": [
                   {"name": "spin", "loop": "loop", "length": 1, "animators": {"b1": {"name": "body", "type": "bone", "keyframes": [
                     {"channel": "rotation", "time": 0, "interpolation": "linear", "data_points": [{"x": 0, "y": 0, "z": 0}]},
                     {"channel": "rotation", "time": 1, "interpolation": "linear", "data_points": [{"x": 0, "y": "90", "z": 0}]}]}}},
                   {"name": "wave", "loop": "once", "animators": {"b1": {"keyframes": [
                     {"channel": "position", "time": 0.5, "interpolation": "step", "data_points": [{"x": 2, "y": "q.anim_time * 10", "z": "v.unknown"}]}]}}}]}
                """.formatted(PNG, extraElements, outliner, groups);
    }

    private static final String INLINE = model("""
            [{"name": "body", "uuid": "b1", "origin": [0, 0, 0], "children": ["c1", "l1"]}]""", "", "");

    private static BbModel parse(String json) {
        return BbModel.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /** The posed faces: 4 vertices of x, y, z, u, v each. */
    private static List<float[]> quads(BbModel m, BbModel.Animation a, float t) {
        List<float[]> out = new ArrayList<>();
        float[] cur = new float[20];
        int[] n = {0};
        m.mesh(a, t, new Matrix4f(), (texture, x, y, z, u, v, nx, ny, nz) -> {
            assertEquals(0, texture);
            assertEquals(1, nx * nx + ny * ny + nz * nz, 1e-4, "normals are unit length");
            System.arraycopy(new float[]{x, y, z, u, v}, 0, cur, n[0] % 4 * 5, 5);
            if (++n[0] % 4 == 0) out.add(cur.clone());
        });
        return out;
    }

    @Test
    void readsCubesTexturesAndAnimations() {
        BbModel m = parse(INLINE);
        assertEquals(1, m.roots.size());
        assertEquals(1, m.roots.get(0).parts.size(), "locators are skipped");
        assertEquals(1, m.textures.size());
        assertEquals(List.of("spin", "wave"), List.copyOf(m.animations.keySet()));
        assertEquals(0.5f, m.animations.get("wave").length(), "no length: the last keyframe");
        assertEquals(0.5f, m.top, 1e-5, "8 px high");
        assertEquals(0.25f, m.width, 1e-5);
        assertEquals((float) Math.sqrt(0.25 * 0.25 * 2 + 0.25), m.radius, 1e-5);

        List<float[]> rest = quads(m, null, 0);
        assertEquals(5, rest.size(), "a face without texture isn't drawn");
        for (float[] q : rest) for (int i = 0; i < 20; i += 5) {
            assertEquals(0.25f, Math.abs(q[i]), 1e-5, "pixels become blocks");
            assertTrue(q[i + 1] == 0 || Math.abs(q[i + 1] - 0.5f) < 1e-5);
            assertTrue(q[i + 3] >= 0 && q[i + 3] <= 0.75f && q[i + 4] >= 0 && q[i + 4] <= 0.5f, "UVs in 0..1 of the texture");
        }
        assertArrayEquals(new float[]{0.25f, 0.5f, -0.25f, 0, 0}, Arrays.copyOf(rest.get(0), 5), 1e-5f, "top-left of the north face");
    }

    @Test
    void posesAnimations() {
        BbModel m = parse(INLINE);
        BbModel.Animation spin = m.animations.get("spin");
        // halfway: 45°, applied negated on Y as Blockbench shows it
        float[] north = quads(m, spin, 0.5f).get(0);
        Vector3f expect = new Matrix4f().rotateY((float) Math.toRadians(-45)).transformPosition(new Vector3f(0.25f, 0.5f, -0.25f));
        assertEquals(expect.x, north[0], 1e-5);
        assertEquals(expect.z, north[2], 1e-5);

        BbModel.Animation wave = m.animations.get("wave");
        assertEquals(0.25f, wave.at(0.25), 1e-6);
        assertEquals(-1, wave.at(0.6), "a 'once' animation ends");
        assertEquals(0, wave.at(-3), "before the start is the start");
        assertEquals(0.5f, spin.at(1.5), 1e-6, "a loop wraps");
        // keyframe at 0.5: x = 2 px (negated), y = q.anim_time * 10 px, an unknown variable is 0
        float[] moved = quads(m, wave, 0.4f).get(0);
        assertEquals(0.25f - 2 / 16f, moved[0], 1e-5);
        assertEquals(0.5f + 4 / 16f, moved[1], 1e-5, "Molang reads the animation's time");
        assertEquals(-0.25f, moved[2], 1e-5);
    }

    private static BbModel.Key key(float time, float value, BbModel.Interp interp, float... bezier) {
        Molang.Expr[] v = {t -> value, t -> value, t -> value};
        float[] b = new float[12];
        System.arraycopy(bezier, 0, b, 0, bezier.length);
        return new BbModel.Key(time, v, v, interp, b);
    }

    @Test
    void sampling() {
        Vector3f out = new Vector3f();
        BbModel.Key[] catmull = {key(0, 0, BbModel.Interp.CATMULLROM), key(1, 10, BbModel.Interp.CATMULLROM)};
        assertTrue(BbModel.sample(catmull, 0.5f, out));
        assertEquals(5, out.x, 1e-5, "two keyframes: catmull-rom is the straight line");
        BbModel.sample(catmull, -1, out);
        assertEquals(0, out.x);
        BbModel.sample(catmull, 2, out);
        assertEquals(10, out.x);
        assertFalse(BbModel.sample(null, 0, out));

        BbModel.Key[] step = {key(0, 0, BbModel.Interp.STEP), key(1, 10, BbModel.Interp.LINEAR)};
        BbModel.sample(step, 0.9f, out);
        assertEquals(0, out.x, "step holds until the next keyframe");

        // bezier with flat handles a third of the way in: slow start and end, the middle on the line
        float[] flat = {-1 / 3f, -1 / 3f, -1 / 3f, 0, 0, 0, 1 / 3f, 1 / 3f, 1 / 3f, 0, 0, 0};
        BbModel.Key[] ease = {key(0, 0, BbModel.Interp.BEZIER, flat), key(1, 10, BbModel.Interp.BEZIER, flat)};
        BbModel.sample(ease, 0.5f, out);
        assertEquals(5, out.x, 1e-3);
        BbModel.sample(ease, 0.1f, out);
        assertTrue(out.x > 0 && out.x < 1, "eases in: " + out.x);
    }

    @Test
    void molang() {
        assertEquals(3, Molang.parse("1 + 2").eval(0));
        assertEquals(-7, Molang.parse("-(1 + 2) * 2 - 1").eval(0));
        assertEquals(1, Molang.parse("math.sin(90)").eval(0), 1e-6, "degrees, as in Molang");
        assertEquals(20, Molang.parse("Query.Anim_Time * 10;").eval(2), 1e-6);
        assertEquals(5, Molang.parse("math.clamp(q.anim_time, 0, 5)").eval(9));
        assertEquals(15, Molang.parse("math.lerp(10, 20, 0.5)").eval(0));
        assertEquals(0, Molang.parse("v.speed * 3").eval(1), "unknown variables are 0");
        assertEquals(0, Molang.parse("1 +").eval(1), "what doesn't parse is 0");
        assertEquals(0, Molang.parse("t.a = 1; return t.a;").eval(1));
        assertEquals(0, Molang.parse("1 / 0").eval(1));
    }

    @Test
    void boxUvAndMeshes() {
        // a 2×4×6 box-UV cube saved without face UVs, mirrored, and a triangle and a quad of a mesh (quad corners stored crossed)
        String extra = """
                , {"uuid": "c2", "type": "cube", "box_uv": true, "mirror_uv": true, "uv_offset": [10, 0], "from": [0, 0, 0], "to": [2, 4, 6],
                   "faces": {"north": {"texture": 0}, "up": {"texture": 0}, "east": {"texture": 0}}},
                  {"uuid": "m1", "type": "mesh", "origin": [0, 16, 0], "rotation": [0, 0, 0],
                   "vertices": {"a": [0, 0, 0], "b": [16, 0, 0], "c": [0, 0, 16], "d": [16, 0, 16]},
                   "faces": {"tri": {"vertices": ["a", "b", "c"], "uv": {"a": [0, 0], "b": [32, 0], "c": [0, 32]}, "texture": 0},
                             "quad": {"vertices": ["a", "d", "b", "c"], "uv": {"a": [0, 0], "b": [32, 0], "c": [0, 32], "d": [32, 32]}, "texture": 0}}}""";
        BbModel m = parse(model("""
                [{"name": "body", "uuid": "b1", "origin": [0, 0, 0], "children": ["c2", "m1"]}]""", "", extra));
        List<BbModel.Part> parts = m.roots.get(0).parts;
        assertEquals(2, parts.size());
        BbModel.Face[] box = parts.get(0).faces();
        assertEquals(3, box.length);
        // north of a box-UV cube at (10, 0) with d = 6: [u+d, v+d, u+d+w, v+d+h] = [16, 6, 18, 10], u mirrored
        assertArrayEquals(new float[]{18 / 32f, 6 / 32f, 18 / 32f, 10 / 32f, 16 / 32f, 10 / 32f, 16 / 32f, 6 / 32f}, box[0].uv(), 1e-6f);
        // mirroring swaps east and west: east takes west's [u+d+w, v+d, u+2d+w, v+d+h] = [18, 6, 24, 10]
        assertEquals(24 / 32f, box[1].uv()[0], 1e-6, "east, mirrored");

        BbModel.Face[] mesh = parts.get(1).faces();
        assertEquals(2, mesh.length);
        assertEquals(1, Math.abs(mesh[0].ny()), 1e-6, "a flat face points up or down");
        float[] p = mesh[1].pos();
        // the quad goes around: consecutive corners never span a diagonal (16√2)
        for (int k = 0; k < 4; k++) {
            int j = (k + 1) % 4;
            double len = Math.hypot(p[k * 3] - p[j * 3], p[k * 3 + 2] - p[j * 3 + 2]);
            assertEquals(16, len, 1e-4, "edge " + k);
        }
        assertEquals(1, m.top, 1e-5, "the mesh sits 16 px up");
    }

    @Test
    void groupsByUuid() {
        // Blockbench 5 lists groups apart and the outliner only names them
        BbModel m = parse(model("""
                [{"uuid": "b1", "children": ["c1"]}]""", """
                "groups": [{"name": "body", "uuid": "b1", "origin": [0, 0, 0], "rotation": [0, 90, 0]}],""", ""));
        assertEquals(1, m.roots.get(0).parts.size());
        assertEquals(90, m.roots.get(0).rotation.y);
    }

    @Test
    void loadErrors() {
        Map<String, byte[]> files = Map.of("models/npc.bbmodel", INLINE.getBytes(StandardCharsets.UTF_8),
                "models/bad.bbmodel", "{\"textures\": [{\"name\": \"t\", \"path\": \"C:/t.png\"}]}".getBytes(StandardCharsets.UTF_8));
        dev.holoweb.troupe.lua.LuaRuntime.Files f = files::get;
        BbModel m = BbModel.load(f, "loja", "npc");
        assertSame(m, BbModel.load(f, "loja", "npc"), "parsed once per version of the files");
        assertEquals("model 'loja:npc' has no animation 'acenar' (it has spin, wave)",
                assertThrows(IllegalArgumentException.class, () -> m.animation("acenar", "loja:npc")).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BbModel.load(f, "loja", "x")).getMessage().contains("models/x.bbmodel"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BbModel.load(f, "loja", "bad")).getMessage().contains("isn't embedded"));
        assertThrows(IllegalArgumentException.class, () -> BbModel.load(f, "loja", "../server/main"));
        assertEquals("loja:npc", BbModel.qualify("loja", "npc"));
        assertEquals("base:npc", BbModel.qualify("loja", "base:npc"));
    }
}

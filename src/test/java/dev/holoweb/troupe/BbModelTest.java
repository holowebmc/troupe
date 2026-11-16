package dev.holoweb.troupe;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BbModelTest {
    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

    /** One bone at (0, 0, 0) holding an 8×8×8 cube on the ground, and a 'spin' animation turning it 90° in 1 s. */
    private static String model(String outliner, String groups) {
        return """
                {"meta": {"format_version": "4.10", "model_format": "free"},
                 "resolution": {"width": 32, "height": 32},
                 "textures": [{"name": "skin", "source": "%s", "uv_width": 32, "uv_height": 32}],
                 "elements": [
                   {"uuid": "c1", "type": "cube", "from": [-4, 0, -4], "to": [4, 8, 4], "origin": [0, 0, 0],
                    "faces": {"north": {"uv": [0, 0, 8, 8], "texture": 0}, "south": {"uv": [8, 0, 16, 8], "texture": 0},
                              "east": {"uv": [0, 8, 8, 16], "texture": 0}, "west": {"uv": [8, 8, 16, 16], "texture": 0},
                              "up": {"uv": [16, 0, 24, 8], "texture": 0}, "down": {"uv": [16, 8, 24, 16], "texture": null}}},
                   {"uuid": "m1", "type": "mesh"}],
                 "outliner": %s, %s
                 "animations": [
                   {"name": "spin", "loop": "loop", "length": 1, "animators": {"b1": {"name": "body", "type": "bone", "keyframes": [
                     {"channel": "rotation", "time": 0, "interpolation": "linear", "data_points": [{"x": 0, "y": 0, "z": 0}]},
                     {"channel": "rotation", "time": 1, "interpolation": "linear", "data_points": [{"x": 0, "y": "90", "z": 0}]}]}}},
                   {"name": "wave", "loop": "once", "animators": {"b1": {"keyframes": [
                     {"channel": "position", "time": 0.5, "interpolation": "step", "data_points": [{"x": 2, "y": "q.anim_time", "z": 0}]}]}}}]}
                """.formatted(PNG, outliner, groups);
    }

    private static final String INLINE = model("""
            [{"name": "body", "uuid": "b1", "origin": [0, 0, 0], "children": ["c1", "m1"]}]""", "");

    private static List<float[]> quads(BbModel m, BbModel.Animation a, float t) {
        List<float[]> out = new ArrayList<>();
        m.mesh(a, t, new Matrix4f(), (texture, corners, nx, ny, nz) -> {
            assertEquals(0, texture);
            assertEquals(1, nx * nx + ny * ny + nz * nz, 1e-4, "normals are unit length");
            out.add(corners);
        });
        return out;
    }

    @Test
    void readsCubesTexturesAndAnimations() {
        BbModel m = BbModel.parse(INLINE.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, m.roots.size());
        assertEquals(1, m.roots.get(0).cubes.size(), "mesh elements are skipped");
        assertEquals(1, m.textures.size());
        assertEquals(List.of("spin", "wave"), List.copyOf(m.animations.keySet()));
        assertEquals(0.5f, m.animations.get("wave").length(), "no length: the last keyframe");

        List<float[]> rest = quads(m, null, 0);
        assertEquals(5, rest.size(), "a face without texture isn't drawn");
        for (float[] q : rest) for (int i = 0; i < 20; i += 5) {
            assertEquals(0.25f, Math.abs(q[i]), 1e-5, "pixels become blocks");
            assertTrue(q[i + 1] == 0 || Math.abs(q[i + 1] - 0.5f) < 1e-5);
            assertTrue(q[i + 3] >= 0 && q[i + 3] <= 0.75f && q[i + 4] >= 0 && q[i + 4] <= 0.5f, "UVs in 0..1 of the texture");
        }
        float[] north = rest.get(0);
        assertArrayEquals(new float[]{0.25f, 0.5f, -0.25f, 0, 0}, java.util.Arrays.copyOf(north, 5), 1e-5f, "top-left of the north face");
    }

    @Test
    void posesAnimations() {
        BbModel m = BbModel.parse(INLINE.getBytes(StandardCharsets.UTF_8));
        BbModel.Animation spin = m.animations.get("spin");
        // halfway: 45°, applied negated on Y as Blockbench shows it
        float[] north = quads(m, spin, 0.5f).get(0);
        Vector3f expect = new Matrix4f().rotateY((float) Math.toRadians(-45)).transformPosition(new Vector3f(0.25f, 0.5f, -0.25f));
        assertEquals(expect.x, north[0], 1e-5);
        assertEquals(expect.z, north[2], 1e-5);

        BbModel.Animation wave = m.animations.get("wave");
        assertEquals(0.25f, wave.at(0.25), 1e-6);
        assertEquals(-1, wave.at(0.6), "a 'once' animation ends");
        assertEquals(0.5f, spin.at(1.5), 1e-6, "a loop wraps");
        // step keyframe at 0.5 with x = 2 px, Molang y = 0; X is negated
        float[] moved = quads(m, wave, 0.4f).get(0);
        assertEquals(0.25f - 2 / 16f, moved[0], 1e-5);
        assertEquals(0.5f, moved[1], 1e-5);
    }

    @Test
    void sampling() {
        BbModel.Key a = new BbModel.Key(0, new Vector3f(0), new Vector3f(0), BbModel.Interp.CATMULLROM);
        BbModel.Key b = new BbModel.Key(1, new Vector3f(10), new Vector3f(10), BbModel.Interp.CATMULLROM);
        BbModel.Key[] keys = {a, b};
        assertEquals(5, BbModel.sample(keys, 0.5f).x, 1e-5, "two keyframes: catmull-rom is the straight line");
        assertEquals(0, BbModel.sample(keys, -1).x);
        assertEquals(10, BbModel.sample(keys, 2).x);
        assertNull(BbModel.sample(null, 0));
    }

    @Test
    void groupsByUuid() {
        // Blockbench 5 lists groups apart and the outliner only names them
        String json = model("""
                [{"uuid": "b1", "children": ["c1"]}]""", """
                "groups": [{"name": "body", "uuid": "b1", "origin": [0, 0, 0], "rotation": [0, 90, 0]}],""");
        BbModel m = BbModel.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, m.roots.get(0).cubes.size());
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

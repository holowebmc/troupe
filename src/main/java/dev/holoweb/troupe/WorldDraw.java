package dev.holoweb.troupe;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes in the world, drawn every frame on the client:
 * <pre>
 * draw.line(id, { from = {x, y, z}, to = {x, y, z}, color, width = 2, dim })
 * draw.box(id, { min = {x, y, z}, max = {x, y, z}, color, fill = false, dim })
 * draw.billboard(id, texture, { pos = {x, y, z}, size = 1, color, dim })      an image from the assets, facing the camera
 * draw.text(id, text, { pos = {x, y, z}, color, scale = 1, background, see_through = false, dim })
 * draw.model(id, 'fonte' | 'outra:fonte', { pos = {x, y, z}, yaw = 0, scale = 1, animation, dim })   a Blockbench model
 *                             from the resource's models/ (see ModelDraw); the animation restarts only when it changes
 * draw.remove(id)  draw.clear()
 * render.on_world(fn(view))   every frame (view = {x, y, z} of the camera); inside it render.line / render.box /
 *                             render.billboard / render.world_text take the same options and draw for that frame only
 * </pre>
 * Colors are 0xRRGGBB (opaque) or 0xAARRGGBB. {@code dim} limits a shape to one dimension. Shapes farther than 256
 * blocks aren't drawn. Line width is ignored before 1.21.11 (the game draws 1-pixel lines there).
 */
final class WorldDraw {
    sealed interface Shape permits Line, Box, Board, Label, Model {
        String dim();

        Vec3 anchor();
    }

    record Line(Vec3 a, Vec3 b, int color, float width, String dim) implements Shape {
        public Vec3 anchor() {
            return a;
        }
    }

    record Box(Vec3 min, Vec3 max, int color, boolean fill, String dim) implements Shape {
        public Vec3 anchor() {
            return min;
        }
    }

    record Board(Identifier texture, Vec3 pos, float size, int color, String dim) implements Shape {
        public Vec3 anchor() {
            return pos;
        }
    }

    record Label(String text, Vec3 pos, int color, float scale, int background, boolean seeThrough, String dim) implements Shape {
        public Vec3 anchor() {
            return pos;
        }
    }

    record Model(ModelDraw.Prop prop, String dim) implements Shape {
        public Vec3 anchor() {
            return prop.pos();
        }
    }

    private static final int MAX_SHAPES = 1024, MAX_FRAME_SHAPES = 4096;
    private static final double MAX_DISTANCE = 256;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final ModuleRegistry modules;
    private final Module render;
    private final ModelDraw models;
    private final Map<String, Map<String, Shape>> shapes = new LinkedHashMap<>();
    private final List<Shape> frame = new ArrayList<>();
    private boolean collecting;

    WorldDraw(ModuleRegistry modules, ResourceHost host) {
        this.modules = modules;
        this.models = new ModelDraw(host);
        modules.global("draw")
                .function("line", a -> keep(a, line(a.table(1))))
                .function("box", a -> keep(a, box(a.table(1))))
                .function("billboard", a -> keep(a, board(a.string(1), a.table(2))))
                .function("text", a -> keep(a, label(a.string(1), a.get(2) == null ? Map.of() : a.table(2))))
                .function("model", a -> keep(a, model(a)))
                .function("remove", a -> {
                    Map<String, Shape> mine = shapes.get(a.resource());
                    if (mine != null) mine.remove(a.string(0));
                    return null;
                })
                .function("clear", a -> {
                    shapes.remove(a.resource());
                    return null;
                });
        render = modules.global("render")
                .function("line", a -> now(line(a.table(0))))
                .function("box", a -> now(box(a.table(0))))
                .function("billboard", a -> now(board(a.string(0), a.table(1))))
                .function("world_text", a -> now(label(a.string(0), a.get(1) == null ? Map.of() : a.table(1))))
                .event("world");
        host.onStop(shapes::remove);
    }

    void clear() {
        shapes.clear();
        models.clear();
    }

    private Object keep(Args a, Shape s) {
        Map<String, Shape> mine = shapes.computeIfAbsent(a.resource(), r -> new LinkedHashMap<>());
        if (!mine.containsKey(a.string(0)) && mine.size() >= MAX_SHAPES) throw new IllegalArgumentException("at most " + MAX_SHAPES + " shapes per resource");
        mine.put(a.string(0), s);
        return null;
    }

    private Object now(Shape s) {
        if (!collecting) throw new IllegalArgumentException("render." + "line/box/billboard/world_text only work inside render.on_world");
        if (frame.size() < MAX_FRAME_SHAPES) frame.add(s);
        return null;
    }

    // ---- options

    private static Line line(Map<String, Object> o) {
        return new Line(vec(o, "from"), vec(o, "to"), ClientHud.color(o, 0xFFFFFF), (float) Math.max(1, Math.min(16, num(o, "width", 2))), dim(o));
    }

    private static Box box(Map<String, Object> o) {
        Vec3 a = vec(o, "min"), b = vec(o, "max");
        Vec3 min = new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)), max = new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
        if (max.subtract(min).length() > 512) throw new IllegalArgumentException("box too big");
        return new Box(min, max, ClientHud.color(o, 0xFFFFFF), Boolean.TRUE.equals(o.get("fill")), dim(o));
    }

    private static Board board(String texture, Map<String, Object> o) {
        return new Board(ServerApi.id(texture), vec(o, "pos"), (float) Math.max(0.01, Math.min(64, num(o, "size", 1))), ClientHud.color(o, 0xFFFFFF), dim(o));
    }

    private static Label label(String text, Map<String, Object> o) {
        if (text.length() > 256) throw new IllegalArgumentException("text too long (256 max)");
        Object bg = o.get("background");
        return new Label(text, vec(o, "pos"), ClientHud.color(o, 0xFFFFFF), (float) Math.max(0.1, Math.min(16, num(o, "scale", 1))),
                bg instanceof Number n ? (int) n.longValue() : 0x40000000, Boolean.TRUE.equals(o.get("see_through")), dim(o));
    }

    private Model model(Args a) {
        String ref = BbModel.qualify(a.resource(), a.string(1));
        Map<String, Object> o = a.table(2);
        String animation = o.get("animation") == null ? null : String.valueOf(o.get("animation"));
        models.check(ref, animation);
        Map<String, Shape> mine = shapes.get(a.resource());
        var level = Minecraft.getInstance().level;
        long tick = mine != null && mine.get(a.string(0)) instanceof Model m && m.prop().ref().equals(ref)
                && java.util.Objects.equals(m.prop().animation(), animation) ? m.prop().tick() : level == null ? 0 : level.getGameTime();
        double scale = num(o, "scale", 1);
        if (!(scale > 0) || scale > 64) throw new IllegalArgumentException("scale must be 0..64");
        return new Model(new ModelDraw.Prop(ref, vec(o, "pos"), (float) num(o, "yaw", 0), (float) scale, animation, tick), dim(o));
    }

    private static Vec3 vec(Map<String, Object> o, String key) {
        if (!(o.get(key) instanceof Map<?, ?> m) || !(m.get("x") instanceof Number x) || !(m.get("y") instanceof Number y) || !(m.get("z") instanceof Number z))
            throw new IllegalArgumentException(key + " must be {x = ..., y = ..., z = ...}");
        return new Vec3(x.doubleValue(), y.doubleValue(), z.doubleValue());
    }

    private static double num(Map<String, Object> o, String key, double fallback) {
        return o.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String dim(Map<String, Object> o) {
        return o.get("dim") == null ? null : String.valueOf(o.get("dim"));
    }

    // ---- drawing

    /** Runs render.on_world, then lists what to draw this frame (retained + this frame's). */
    private List<Shape> collect(Vec3 cam) {
        frame.clear();
        if (modules.listens(render, "world")) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("x", cam.x);
            view.put("y", cam.y);
            view.put("z", cam.z);
            collecting = true;
            try {
                modules.emitCancellable(render, "world", view);
            } finally {
                collecting = false;
            }
        }
        var level = Minecraft.getInstance().level;
        String here = level == null ? null : Compat.dimension(level);
        List<Shape> out = new ArrayList<>();
        for (Map<String, Shape> mine : shapes.values()) for (Shape s : mine.values()) if (visible(s, cam, here)) out.add(s);
        for (Shape s : frame) if (visible(s, cam, here)) out.add(s);
        return out;
    }

    private static boolean visible(Shape s, Vec3 cam, String here) {
        return (s.dim() == null || s.dim().equals(here)) && s.anchor().distanceTo(cam) <= MAX_DISTANCE;
    }

    private static int a(int c) {
        return c >>> 24;
    }

    private static int r(int c) {
        return c >> 16 & 0xFF;
    }

    private static int g(int c) {
        return c >> 8 & 0xFF;
    }

    private static int b(int c) {
        return c & 0xFF;
    }

    /** The 12 edges of a box, as pairs of corners. */
    private static void edges(Box b, Vec3 cam, EdgeSink sink) {
        double x0 = b.min().x - cam.x, y0 = b.min().y - cam.y, z0 = b.min().z - cam.z;
        double x1 = b.max().x - cam.x, y1 = b.max().y - cam.y, z1 = b.max().z - cam.z;
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] e = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] p : e) sink.edge(c[p[0]], c[p[1]]);
    }

    private interface EdgeSink {
        void edge(double[] a, double[] b);
    }

    /** The 6 faces of a box, 4 corners each (counter-clockwise from outside). */
    private static double[][][] faces(Box b, Vec3 cam) {
        double x0 = b.min().x - cam.x, y0 = b.min().y - cam.y, z0 = b.min().z - cam.z;
        double x1 = b.max().x - cam.x, y1 = b.max().y - cam.y, z1 = b.max().z - cam.z;
        return new double[][][]{
                {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}},
                {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}},
                {{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}},
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}},
                {{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}},
                {{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}}};
    }

    //? if >=1.21.9 {
    /**
     * {@code LevelRenderEvents.COLLECT_SUBMITS} ({@code WorldRenderEvents.BEFORE_ENTITIES} before 26.1): everything
     * goes through the frame's submit collector.
     */
    //? if >=26.1 {
    void render(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext ctx) {
        var camState = ctx.levelState().cameraRenderState;
        PoseStack ps = ctx.poseStack();
        var collector = ctx.submitNodeCollector();
    //?} else {
    /*void render(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext ctx) {
        var camState = ctx.worldState().cameraRenderState;
        PoseStack ps = ctx.matrices();
        var collector = ctx.commandQueue();
    *///?}
        Vec3 cam = camState.pos;
        List<Shape> shapes = collect(cam);
        var frame = models.collect(cam, new org.joml.Vector3f(0, 0, -1).rotate(camState.orientation), props(shapes));
        frame.meshes().forEach((texture, mesh) -> {
            if (mesh.count > 0) collector.submitCustomGeometry(ps, cutout(texture), mesh::write);
        });
        if (frame.shadows().count > 0) collector.submitCustomGeometry(ps, translucent(ModelDraw.SHADOW), frame.shadows()::write);
        shapes.addAll(frame.names());
        if (shapes.isEmpty()) return;
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.lines(), (pose, vc) -> {
            for (Shape s : shapes) {
                if (s instanceof Line l) line(pose, vc, sub(l.a(), cam), sub(l.b(), cam), l.color(), l.width());
                else if (s instanceof Box b && !b.fill()) edges(b, cam, (p, q) -> line(pose, vc, p, q, b.color(), 2));
            }
        });
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), (pose, vc) -> {
            for (Shape s : shapes) if (s instanceof Box b && b.fill())
                for (double[][] f : faces(b, cam)) for (double[] p : f) corner(pose, vc, p, b.color());
        });
        Font font = Minecraft.getInstance().font;
        for (Shape s : shapes) {
            if (s instanceof Board bd) {
                ps.pushPose();
                ps.translate(bd.pos().x - cam.x, bd.pos().y - cam.y, bd.pos().z - cam.z);
                turn(ps, camState.orientation);
                collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.text(bd.texture()), (pose, vc) -> board(pose, vc, bd));
                ps.popPose();
            } else if (s instanceof Label lb) {
                ps.pushPose();
                ps.translate(lb.pos().x - cam.x, lb.pos().y - cam.y, lb.pos().z - cam.z);
                turn(ps, camState.orientation);
                ps.scale(0.025f * lb.scale(), -0.025f * lb.scale(), 0.025f * lb.scale());
                var seq = net.minecraft.util.FormattedCharSequence.forward(lb.text(), net.minecraft.network.chat.Style.EMPTY);
                collector.submitText(ps, -font.width(seq) / 2f, 0, seq, false,
                        lb.seeThrough() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, FULL_BRIGHT, lb.color(), lb.background(), 0);
                ps.popPose();
            }
        }
    }
    //?} else {
    /*/^* {@code WorldRenderEvents.AFTER_ENTITIES}: drawn right away through the frame's buffers. *^/
    void render(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext ctx) {
        var camera = ctx.camera();
        Vec3 cam = camera.getPosition();
        List<Shape> shapes = collect(cam);
        if (ctx.consumers() == null) return;
        PoseStack ps = ctx.matrixStack();
        var buffers = ctx.consumers();
        PoseStack.Pose pose = ps.last();
        var frame = models.collect(cam, new org.joml.Vector3f(camera.getLookVector()), props(shapes));
        frame.meshes().forEach((texture, mesh) -> {
            if (mesh.count > 0) mesh.write(pose, buffers.getBuffer(cutout(texture)));
        });
        if (frame.shadows().count > 0) frame.shadows().write(pose, buffers.getBuffer(translucent(ModelDraw.SHADOW)));
        shapes.addAll(frame.names());
        if (shapes.isEmpty() && frame.empty()) return;
        VertexConsumer lines = buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines());
        for (Shape s : shapes) {
            if (s instanceof Line l) line(pose, lines, sub(l.a(), cam), sub(l.b(), cam), l.color(), l.width());
            else if (s instanceof Box b && !b.fill()) edges(b, cam, (p, q) -> line(pose, lines, p, q, b.color(), 2));
        }
        VertexConsumer quads = buffers.getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
        for (Shape s : shapes) if (s instanceof Box b && b.fill())
            for (double[][] f : faces(b, cam)) for (double[] p : f) corner(pose, quads, p, b.color());
        Font font = Minecraft.getInstance().font;
        for (Shape s : shapes) {
            if (s instanceof Board bd) {
                ps.pushPose();
                ps.translate(bd.pos().x - cam.x, bd.pos().y - cam.y, bd.pos().z - cam.z);
                ps.mulPose(camera.rotation());
                ps.scale(MIRROR, 1, 1);
                board(ps.last(), buffers.getBuffer(net.minecraft.client.renderer.RenderType.text(bd.texture())), bd);
                ps.popPose();
            } else if (s instanceof Label lb) {
                ps.pushPose();
                ps.translate(lb.pos().x - cam.x, lb.pos().y - cam.y, lb.pos().z - cam.z);
                ps.mulPose(camera.rotation());
                ps.scale(MIRROR * 0.025f * lb.scale(), -0.025f * lb.scale(), 0.025f * lb.scale());
                font.drawInBatch(lb.text(), -font.width(lb.text()) / 2f, 0, lb.color(), false, ps.last().pose(), buffers,
                        lb.seeThrough() ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, lb.background(), FULL_BRIGHT);
                ps.popPose();
            }
        }
        if (buffers instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource bs) bs.endBatch();
    }
    *///?}

    private static List<ModelDraw.Prop> props(List<Shape> shapes) {
        List<ModelDraw.Prop> props = new ArrayList<>();
        for (Shape s : shapes) if (s instanceof Model m) props.add(m.prop());
        return props;
    }

    /** Entity textures without back-face culling (Blockbench faces can be seen from inside, like planes). */
    //? if >=26.1 {
    private static net.minecraft.client.renderer.rendertype.RenderType cutout(Identifier texture) {
        return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture);
    }
    //?} else if >=1.21.9 {
    /*private static net.minecraft.client.renderer.rendertype.RenderType cutout(Identifier texture) {
        return net.minecraft.client.renderer.rendertype.RenderTypes.entityCutoutNoCull(texture);
    }
    *///?} else {
    /*private static net.minecraft.client.renderer.RenderType cutout(Identifier texture) {
        return net.minecraft.client.renderer.RenderType.entityCutoutNoCull(texture);
    }
    *///?}

    /** For the shadows under dressed entities. */
    //? if >=1.21.9 {
    private static net.minecraft.client.renderer.rendertype.RenderType translucent(Identifier texture) {
        return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture);
    }
    //?} else {
    /*private static net.minecraft.client.renderer.RenderType translucent(Identifier texture) {
        return net.minecraft.client.renderer.RenderType.entityTranslucent(texture);
    }
    *///?}

    /** Before 1.20.5 the camera looks down +Z with +X to its left: what faces it is mirrored back. */
    //? if >=1.20.5 {
    private static final float MIRROR = 1;
    //?} else
    /*private static final float MIRROR = -1;*/

    /** {@code width}: per line from 1.21.11; before, the game draws every line at its own fixed width. */
    private static void line(PoseStack.Pose pose, VertexConsumer vc, double[] p, double[] q, int color, float width) {
        float nx = (float) (q[0] - p[0]), ny = (float) (q[1] - p[1]), nz = (float) (q[2] - p[2]);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len;
        ny /= len;
        nz /= len;
        //? if >=1.21.11 {
        vc.addVertex(pose, (float) p[0], (float) p[1], (float) p[2]).setColor(r(color), g(color), b(color), a(color)).setNormal(pose, nx, ny, nz).setLineWidth(width);
        vc.addVertex(pose, (float) q[0], (float) q[1], (float) q[2]).setColor(r(color), g(color), b(color), a(color)).setNormal(pose, nx, ny, nz).setLineWidth(width);
        //?} else if >=1.21 {
        /*vc.addVertex(pose, (float) p[0], (float) p[1], (float) p[2]).setColor(r(color), g(color), b(color), a(color)).setNormal(pose, nx, ny, nz);
        vc.addVertex(pose, (float) q[0], (float) q[1], (float) q[2]).setColor(r(color), g(color), b(color), a(color)).setNormal(pose, nx, ny, nz);
        *///?} else {
        /*vc.vertex(pose.pose(), (float) p[0], (float) p[1], (float) p[2]).color(r(color), g(color), b(color), a(color)).normal(pose.normal(), nx, ny, nz).endVertex();
        vc.vertex(pose.pose(), (float) q[0], (float) q[1], (float) q[2]).color(r(color), g(color), b(color), a(color)).normal(pose.normal(), nx, ny, nz).endVertex();
        *///?}
    }

    private static void turn(PoseStack ps, org.joml.Quaternionf q) {
        //? if >=26.2 {
        ps.rotate(q);
        //?} else
        /*ps.mulPose(q);*/
    }

    /** One corner of a filled face. */
    private static void corner(PoseStack.Pose pose, VertexConsumer vc, double[] p, int c) {
        //? if >=1.21 {
        vc.addVertex(pose, (float) p[0], (float) p[1], (float) p[2]).setColor(r(c), g(c), b(c), a(c));
        //?} else
        /*vc.vertex(pose.pose(), (float) p[0], (float) p[1], (float) p[2]).color(r(c), g(c), b(c), a(c)).endVertex();*/
    }

    private static void board(PoseStack.Pose pose, VertexConsumer vc, Board bd) {
        float s = bd.size() / 2;
        int c = bd.color();
        //? if >=1.21 {
        vc.addVertex(pose, -s, s, 0).setColor(r(c), g(c), b(c), a(c)).setUv(0, 0).setLight(FULL_BRIGHT);
        vc.addVertex(pose, -s, -s, 0).setColor(r(c), g(c), b(c), a(c)).setUv(0, 1).setLight(FULL_BRIGHT);
        vc.addVertex(pose, s, -s, 0).setColor(r(c), g(c), b(c), a(c)).setUv(1, 1).setLight(FULL_BRIGHT);
        vc.addVertex(pose, s, s, 0).setColor(r(c), g(c), b(c), a(c)).setUv(1, 0).setLight(FULL_BRIGHT);
        //?} else {
        /*vc.vertex(pose.pose(), -s, s, 0).color(r(c), g(c), b(c), a(c)).uv(0, 0).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(pose.pose(), -s, -s, 0).color(r(c), g(c), b(c), a(c)).uv(0, 1).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(pose.pose(), s, -s, 0).color(r(c), g(c), b(c), a(c)).uv(1, 1).uv2(FULL_BRIGHT).endVertex();
        vc.vertex(pose.pose(), s, s, 0).color(r(c), g(c), b(c), a(c)).uv(1, 0).uv2(FULL_BRIGHT).endVertex();
        *///?}
    }

    private static double[] sub(Vec3 p, Vec3 cam) {
        return new double[]{p.x - cam.x, p.y - cam.y, p.z - cam.z};
    }
}

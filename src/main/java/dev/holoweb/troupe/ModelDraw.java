package dev.holoweb.troupe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.holoweb.troupe.lua.ResourceHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Blockbench models on the client: on entities the server dressed ({@code entity:set_model}, which arrives as entity
 * state) in place of the game's own renderer ({@code EntityRenderDispatcherMixin} hides the entity), and the props
 * of {@code draw.model}. Textures are uploaded once per model version and released when a resource stops.
 */
public final class ModelDraw {
    private static final Logger LOG = LoggerFactory.getLogger("troupe");
    private static final double MAX_DISTANCE = 128;
    private static final int MAX_QUADS = 100_000;

    /** A prop from {@code draw.model}: {@code ref} is {@code resource:name}. */
    record Prop(String ref, Vec3 pos, float yaw, float scale, String animation, long since) {}

    /** One face ready to write: 4 × (x, y, z, u, v) relative to the camera, its normal, light and overlay. */
    record Quad(float[] v, float nx, float ny, float nz, int light, int overlay) {}

    private record Uploaded(BbModel model, Identifier[] textures) {}

    /** What the server said about one entity; {@code model} is null until {@code troupe:model} arrives. */
    private static final class Dress {
        final String resource;
        String model, idle, walk, animation;
        float scale = 1;
        long since;
        boolean broken;

        Dress(String resource) {
            this.resource = resource;
        }
    }

    private static final Map<UUID, Dress> dressed = new HashMap<>();
    private final ResourceHost host;
    private final Map<String, Uploaded> uploaded = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private int next;

    ModelDraw(ResourceHost host) {
        this.host = host;
        host.onEntityState(this::entityState);
        host.onStop(resource -> uploaded.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(resource + ":")) return false;
            release(e.getValue());
            return true;
        }));
    }

    /** The game skips its own renderer for an entity drawn as a model. */
    public static boolean hides(Entity e) {
        Dress d = dressed.isEmpty() ? null : dressed.get(e.getUUID());
        return d != null && d.model != null && !d.broken;
    }

    void clear() {
        dressed.clear();
        uploaded.values().forEach(ModelDraw::release);
        uploaded.clear();
        warned.clear();
    }

    private void entityState(String resource, String entity, String key, String json) {
        UUID id;
        try {
            id = UUID.fromString(entity);
        } catch (IllegalArgumentException e) {
            return;
        }
        Dress d = dressed.get(id);
        if (key == null || json == null && GameApi.MODEL.equals(key)) { // out of sight, or no model anymore
            if (d != null && d.resource.equals(resource)) dressed.remove(id);
            return;
        }
        if (!key.equals(GameApi.MODEL) && !key.equals(GameApi.ANIM)) return;
        if (json == null) {
            if (d != null && d.resource.equals(resource)) d.animation = null;
            return;
        }
        if (d == null || !d.resource.equals(resource)) dressed.put(id, d = new Dress(resource)); // the last resource to dress it wins
        try { // the server's word, but still checked
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (key.equals(GameApi.MODEL)) {
                d.model = o.get("model").getAsString();
                d.scale = Math.max(0.01f, Math.min(16, o.get("scale").getAsFloat()));
                d.idle = o.has("idle") ? o.get("idle").getAsString() : null;
                d.walk = o.has("walk") ? o.get("walk").getAsString() : null;
                d.broken = get(d.model) == null;
            } else {
                d.animation = o.get("name").getAsString();
                d.since = System.nanoTime();
            }
        } catch (RuntimeException e) {
            dressed.remove(id);
        }
    }

    /** The model with its textures on the GPU, or null (logged once) when it can't be drawn. */
    private Uploaded get(String ref) {
        BbModel m;
        try {
            m = BbModel.load(host, ref);
        } catch (RuntimeException e) {
            if (warned.add(ref)) LOG.warn("Model {}: {}", ref, e.getMessage());
            return null;
        }
        Uploaded u = uploaded.get(ref);
        if (u != null && u.model() == m) return u;
        if (u != null) release(u);
        Identifier[] ids = new Identifier[m.textures.size()];
        try {
            for (int i = 0; i < ids.length; i++) {
                NativeImage image = NativeImage.read(m.textures.get(i).png());
                ids[i] = ServerApi.id("troupe:models/" + next++);
                ClientCompat.registerTexture(ids[i], image);
            }
        } catch (IOException | RuntimeException e) {
            for (Identifier id : ids) if (id != null) Minecraft.getInstance().getTextureManager().release(id);
            if (warned.add(ref)) LOG.warn("Model {}: a texture can't be read ({})", ref, e.getMessage());
            return null;
        }
        uploaded.put(ref, u = new Uploaded(m, ids));
        return u;
    }

    private static void release(Uploaded u) {
        for (Identifier id : u.textures()) Minecraft.getInstance().getTextureManager().release(id);
    }

    /** Checks a model and an animation for {@code draw.model}, so a script hears about a typo right away. */
    void check(String ref, String animation) {
        BbModel m = BbModel.load(host, ref);
        if (animation != null) m.animation(animation, ref);
    }

    // ---- drawing

    /** Everything to draw this frame, by texture. */
    Map<Identifier, List<Quad>> collect(Vec3 cam, List<Prop> props) {
        Map<Identifier, List<Quad>> out = new LinkedHashMap<>();
        var mc = Minecraft.getInstance();
        if (mc.level == null || dressed.isEmpty() && props.isEmpty()) return out;
        float partial = ClientCompat.partialTick();
        double now = System.nanoTime() / 1e9;
        int[] quads = {0};
        for (Prop p : props) {
            Uploaded u = get(p.ref());
            if (u == null) continue;
            BbModel.Animation a = p.animation() == null ? null : u.model().animations.get(p.animation());
            float t = a == null ? 0 : a.at(now - p.since() / 1e9);
            Matrix4f root = new Matrix4f().translate((float) (p.pos().x - cam.x), (float) (p.pos().y - cam.y), (float) (p.pos().z - cam.z))
                    .rotateY((float) Math.toRadians(180 - p.yaw())).scale(p.scale());
            add(out, u, t < 0 ? null : a, Math.max(t, 0), root, ClientCompat.light(mc.level, BlockPos.containing(p.pos())), OverlayTexture.NO_OVERLAY, quads);
        }
        if (dressed.isEmpty()) return out;
        boolean firstPerson = !ClientCompat.cameraDetached();
        for (Entity e : mc.level.entitiesForRendering()) {
            Dress d = dressed.get(e.getUUID());
            if (d == null || d.model == null || d.broken || firstPerson && e == mc.getCameraEntity()) continue;
            Vec3 pos = e.getPosition(partial);
            if (pos.distanceTo(cam) > MAX_DISTANCE) continue;
            Uploaded u = get(d.model);
            if (u == null) continue;
            // an animation the server asked for plays over idle / walk until it ends
            BbModel.Animation a = d.animation == null ? null : u.model().animations.get(d.animation);
            float t = a == null ? -1 : a.at(now - d.since / 1e9);
            if (t < 0) {
                d.animation = null;
                double dx = e.getX() - e.xo, dz = e.getZ() - e.zo;
                String auto = dx * dx + dz * dz > 1e-4 && d.walk != null ? d.walk : d.idle;
                a = auto == null ? null : u.model().animations.get(auto);
                t = a == null ? 0 : Math.max(0, a.at(now));
            }
            float yaw = e instanceof LivingEntity l ? Mth.rotLerp(partial, l.yBodyRotO, l.yBodyRot) : Mth.rotLerp(partial, e.yRotO, e.getYRot());
            Matrix4f root = new Matrix4f().translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z))
                    .rotateY((float) Math.toRadians(180 - yaw)).scale(d.scale);
            boolean hurt = e instanceof LivingEntity l && (l.hurtTime > 0 || l.deathTime > 0);
            add(out, u, a, t, root, mc.getEntityRenderDispatcher().getPackedLightCoords(e, partial),
                    hurt ? OverlayTexture.pack(OverlayTexture.u(0), OverlayTexture.v(true)) : OverlayTexture.NO_OVERLAY, quads);
        }
        return out;
    }

    private static void add(Map<Identifier, List<Quad>> out, Uploaded u, BbModel.Animation a, float t, Matrix4f root, int light, int overlay, int[] count) {
        u.model().mesh(a, t, root, (texture, corners, nx, ny, nz) -> {
            if (++count[0] > MAX_QUADS) return;
            out.computeIfAbsent(u.textures()[texture], k -> new ArrayList<>()).add(new Quad(corners, nx, ny, nz, light, overlay));
        });
    }

    static void quad(PoseStack.Pose pose, VertexConsumer vc, Quad q) {
        float[] v = q.v();
        for (int i = 0; i < 20; i += 5) {
            //? if >=1.21 {
            vc.addVertex(pose, v[i], v[i + 1], v[i + 2]).setColor(255, 255, 255, 255).setUv(v[i + 3], v[i + 4])
                    .setOverlay(q.overlay()).setLight(q.light()).setNormal(pose, q.nx(), q.ny(), q.nz());
            //?} else
            /*vc.vertex(pose.pose(), v[i], v[i + 1], v[i + 2]).color(255, 255, 255, 255).uv(v[i + 3], v[i + 4]).overlayCoords(q.overlay()).uv2(q.light()).normal(pose.normal(), q.nx(), q.ny(), q.nz()).endVertex();*/
        }
    }
}

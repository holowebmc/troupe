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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Blockbench models on the client: on entities the server dressed ({@code entity:set_model}, which arrives as entity
 * state) in place of the game's own renderer ({@code EntityRenderDispatcherMixin} hides the entity, so its name tag
 * and shadow are drawn here), and the props of {@code draw.model}. Textures are uploaded once per model version and
 * released when a resource stops. Animations run on the game's clock, which the server keeps in step on every client:
 * everyone sees the same frame, and who comes into view mid-animation joins it there.
 */
public final class ModelDraw {
    private static final Logger LOG = LoggerFactory.getLogger("troupe");
    private static final double MAX_DISTANCE = 128, NAME_DISTANCE = 64, SHADOW_DISTANCE = 48;
    /** Half the angle of the view cone kept: wider than any field of view the game allows, so only what's well off screen goes. */
    private static final double CONE_COS = Math.cos(Math.toRadians(80));
    private static final int MAX_VERTICES = 400_000, FULL_BRIGHT = 0xF000F0;
    static final Identifier SHADOW = ServerApi.id("minecraft:textures/misc/shadow.png");

    /** A prop from {@code draw.model}: {@code ref} is {@code resource:name}; {@code tick}: game time it (re)started. */
    record Prop(String ref, Vec3 pos, float yaw, float scale, String animation, long tick) {}

    /**
     * The vertices to write for one texture this frame: x, y, z, u, v, nx, ny, nz and light, overlay, color each.
     * Kept from frame to frame, so drawing allocates nothing once they're big enough.
     */
    static final class Mesh {
        float[] f = new float[1024 * 8];
        int[] i = new int[1024 * 3];
        int count;

        void add(float x, float y, float z, float u, float v, float nx, float ny, float nz, int light, int overlay, int color) {
            if (count * 8 == f.length) {
                f = Arrays.copyOf(f, f.length * 2);
                i = Arrays.copyOf(i, i.length * 2);
            }
            int a = count * 8, b = count * 3;
            f[a] = x;
            f[a + 1] = y;
            f[a + 2] = z;
            f[a + 3] = u;
            f[a + 4] = v;
            f[a + 5] = nx;
            f[a + 6] = ny;
            f[a + 7] = nz;
            i[b] = light;
            i[b + 1] = overlay;
            i[b + 2] = color;
            count++;
        }

        void write(PoseStack.Pose pose, VertexConsumer vc) {
            for (int k = 0; k < count; k++) {
                int a = k * 8, b = k * 3, c = i[b + 2];
                //? if >=1.21 {
                vc.addVertex(pose, f[a], f[a + 1], f[a + 2]).setColor(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, c >>> 24).setUv(f[a + 3], f[a + 4])
                        .setOverlay(i[b + 1]).setLight(i[b]).setNormal(pose, f[a + 5], f[a + 6], f[a + 7]);
                //?} else
                /*vc.vertex(pose.pose(), f[a], f[a + 1], f[a + 2]).color(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, c >>> 24).uv(f[a + 3], f[a + 4]).overlayCoords(i[b + 1]).uv2(i[b]).normal(pose.normal(), f[a + 5], f[a + 6], f[a + 7]).endVertex();*/
            }
        }
    }

    /** One frame: model faces by texture (cutout), shadows (translucent), and name tags for the label pass. */
    record Frame(Map<Identifier, Mesh> meshes, Mesh shadows, List<WorldDraw.Label> names) {
        boolean empty() {
            if (shadows.count > 0 || !names.isEmpty()) return false;
            for (Mesh m : meshes.values()) if (m.count > 0) return false;
            return true;
        }
    }

    private record Uploaded(BbModel model, Identifier[] textures) {}

    /** What the server said about one entity; {@code model} is null until {@code troupe:model} arrives. */
    private static final class Dress {
        final String resource;
        String model, idle, walk, animation;
        float scale = 1;
        long tick;
        boolean broken;

        Dress(String resource) {
            this.resource = resource;
        }
    }

    private static final Map<UUID, Dress> dressed = new HashMap<>();
    private final ResourceHost host;
    private final Map<String, Uploaded> uploaded = new HashMap<>();
    private final Map<Identifier, Mesh> meshes = new LinkedHashMap<>();
    private final Mesh shadows = new Mesh();
    private final Set<String> warned = new HashSet<>();
    private final Matrix4f root = new Matrix4f();
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
        uploaded.values().forEach(this::release);
        uploaded.clear();
        warned.clear();
    }

    private static long gameTime() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime();
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
                d.tick = o.has("tick") ? o.get("tick").getAsLong() : gameTime();
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

    private void release(Uploaded u) {
        for (Identifier id : u.textures()) {
            Minecraft.getInstance().getTextureManager().release(id);
            meshes.remove(id);
        }
    }

    /** Checks a model and an animation for {@code draw.model}, so a script hears about a typo right away. */
    void check(String ref, String animation) {
        BbModel m = BbModel.load(host, ref);
        if (animation != null) m.animation(animation, ref);
    }

    // ---- drawing

    /** Everything to draw this frame. {@code forward}: where the camera looks. */
    Frame collect(Vec3 cam, Vector3f forward, List<Prop> props) {
        for (Mesh m : meshes.values()) m.count = 0;
        shadows.count = 0;
        List<WorldDraw.Label> names = new ArrayList<>();
        Frame frame = new Frame(meshes, shadows, names);
        var mc = Minecraft.getInstance();
        if (mc.level == null || dressed.isEmpty() && props.isEmpty()) return frame;
        float partial = ClientCompat.partialTick();
        long ticks = mc.level.getGameTime();
        for (Prop p : props) {
            Uploaded u = get(p.ref());
            if (u == null || !visible(p.pos(), cam, forward, u.model().radius * p.scale())) continue;
            BbModel.Animation a = p.animation() == null ? null : u.model().animations.get(p.animation());
            float t = a == null ? 0 : a.at((ticks - p.tick() + partial) / 20.0);
            pose(u, p.pos(), cam, p.yaw(), p.scale(), t < 0 ? null : a, Math.max(t, 0),
                    // lit from half its height, as the game lights entities from their eyes: the feet can be in the ground
                    ClientCompat.light(mc.level, BlockPos.containing(p.pos().add(0, u.model().top * p.scale() / 2, 0))), OverlayTexture.NO_OVERLAY);
        }
        if (dressed.isEmpty()) return frame;
        Entity camera = mc.getCameraEntity();
        boolean firstPerson = !ClientCompat.cameraDetached();
        for (Entity e : mc.level.entitiesForRendering()) {
            Dress d = dressed.get(e.getUUID());
            if (d == null || d.model == null || d.broken || firstPerson && e == camera || mc.player != null && e.isInvisibleTo(mc.player)) continue;
            Vec3 pos = e.getPosition(partial);
            Uploaded u = get(d.model);
            if (u == null) continue;
            BbModel m = u.model();
            if (!visible(pos, cam, forward, m.radius * d.scale * 1.5f + e.getBbHeight())) continue; // animations reach past the rest pose
            // an animation the server asked for plays over idle / walk until it ends
            BbModel.Animation a = d.animation == null ? null : m.animations.get(d.animation);
            float t = a == null ? -1 : a.at((ticks - d.tick + partial) / 20.0);
            if (t < 0) {
                double dx = e.getX() - e.xo, dz = e.getZ() - e.zo;
                String auto = dx * dx + dz * dz > 1e-4 && d.walk != null ? d.walk : d.idle;
                a = auto == null ? null : m.animations.get(auto);
                t = a == null ? 0 : Math.max(0, a.at((ticks + partial) / 20.0));
            }
            float yaw = e instanceof LivingEntity l ? Mth.rotLerp(partial, l.yBodyRotO, l.yBodyRot) : Mth.rotLerp(partial, e.yRotO, e.getYRot());
            boolean hurt = e instanceof LivingEntity l && (l.hurtTime > 0 || l.deathTime > 0);
            pose(u, pos, cam, yaw, d.scale, a, t, mc.getEntityRenderDispatcher().getPackedLightCoords(e, partial),
                    hurt ? OverlayTexture.pack(OverlayTexture.u(0), OverlayTexture.v(true)) : OverlayTexture.NO_OVERLAY);
            double dist = pos.distanceTo(cam);
            // onGround() isn't synced for mobs that never moved: look for ground under the feet instead
            if (dist < SHADOW_DISTANCE && !mc.level.isEmptyBlock(BlockPos.containing(pos.x, pos.y - 0.05, pos.z))) shadow(pos, cam, Math.max(0.25f, Math.min(3, m.width * d.scale)), (float) (1 - dist / SHADOW_DISTANCE));
            if (e != camera && dist < NAME_DISTANCE && (e.isCustomNameVisible() || e.hasCustomName() && e == mc.crosshairPickEntity || e instanceof Player))
                name(names, e, pos.add(0, Math.max(m.top * d.scale, e.getBbHeight()) + 0.5, 0));
        }
        return frame;
    }

    /** Within reach, and not well off screen (a cone around the view, the model as a sphere of {@code radius}). */
    private static boolean visible(Vec3 pos, Vec3 cam, Vector3f forward, float radius) {
        double dx = pos.x - cam.x, dy = pos.y - cam.y, dz = pos.z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > MAX_DISTANCE + radius) return false;
        return dist <= radius || dx * forward.x + dy * forward.y + dz * forward.z >= dist * CONE_COS - radius;
    }

    private void pose(Uploaded u, Vec3 pos, Vec3 cam, float yaw, float scale, BbModel.Animation a, float t, int light, int overlay) {
        root.translation((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z)).rotateY((float) Math.toRadians(180 - yaw)).scale(scale);
        Mesh[] target = new Mesh[u.textures().length];
        for (int i = 0; i < target.length; i++) target[i] = meshes.computeIfAbsent(u.textures()[i], k -> new Mesh());
        int[] budget = {MAX_VERTICES - total()};
        u.model().mesh(a, t, root, (texture, x, y, z, uu, vv, nx, ny, nz) -> {
            if (--budget[0] >= 0) target[texture].add(x, y, z, uu, vv, nx, ny, nz, light, overlay, 0xFFFFFFFF);
        });
    }

    private int total() {
        int n = 0;
        for (Mesh m : meshes.values()) n += m.count;
        return n;
    }

    /** A soft dark disc under the feet, fading with distance (the game's shadow texture). */
    private void shadow(Vec3 pos, Vec3 cam, float r, float strength) {
        float x = (float) (pos.x - cam.x), y = (float) (pos.y - cam.y) + 0.015f, z = (float) (pos.z - cam.z);
        int color = (int) (0.6f * strength * 255) << 24 | 0xFFFFFF;
        shadows.add(x - r, y, z - r, 0, 0, 0, 1, 0, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        shadows.add(x - r, y, z + r, 0, 1, 0, 1, 0, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        shadows.add(x + r, y, z + r, 1, 1, 0, 1, 0, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
        shadows.add(x + r, y, z - r, 1, 0, 0, 1, 0, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, color);
    }

    /** Like the game's: faint through walls unless sneaking, plain on top. */
    private static void name(List<WorldDraw.Label> names, Entity e, Vec3 at) {
        String text = e.getDisplayName().getString();
        if (text.isEmpty()) return;
        if (!e.isDiscrete()) names.add(new WorldDraw.Label(text, at, 0x20FFFFFF, 1, 0x40000000, true, null));
        names.add(new WorldDraw.Label(text, at, 0xFFFFFFFF, 1, e.isDiscrete() ? 0x40000000 : 0, false, null));
    }
}

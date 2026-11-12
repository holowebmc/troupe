package dev.holoweb.troupe;

//? if >=26.2 {
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Draws what {@code ui.slot} put in a page's slot (see {@code lua/UiApi} for the vocabulary), the way a Java
 * {@code Vitrine.Bind} would: each thing gets the slot's pose (origin at the centre, one unit per pixel, y down),
 * moved by its own {@code transform}, and is submitted to the frame's collector. Ids that don't exist, entities
 * that are gone and empty inventory slots show nothing.
 */
final class SlotDraw {
    private static final ItemStackRenderState ITEM = new ItemStackRenderState(); // one thing is drawn before the next is asked for
    private static final BlockDisplayContext BLOCK_CONTEXT = BlockDisplayContext.create();
    private static final Map<String, Lighting.Entry> LIGHTING = Map.of("entity", Lighting.Entry.ENTITY_IN_UI, "flat", Lighting.Entry.ITEMS_FLAT,
            "3d", Lighting.Entry.ITEMS_3D, "level", Lighting.Entry.LEVEL, "skin", Lighting.Entry.PLAYER_SKIN);
    private static final int MAX_OPS = 32;
    /** One stand-in per entity type, never in the world (null: the type can't be made or drawn). Dropped with the level. */
    private static final Map<EntityType<?>, Entity> STANDINS = new HashMap<>();
    private static Level standinsOf;
    private static BlockModelResolver blocks;

    private SlotDraw() {}

    /** {@code pose}: origin at the centre of the slot, one unit per pixel, y down. The page's yaw / pitch add to each thing's. */
    static void draw(List<Map<String, Object>> things, int width, int height, float pageYaw, float pagePitch, PoseStack pose, SubmitNodeCollector nodes) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return; // item components are only bound once in a world
        for (Map<String, Object> t : things) {
            pose.pushPose();
            transform(t, pose);
            Quaternionf turn = new Quaternionf().rotateZ(rad(number(t, "roll", 0))).rotateX(rad(number(t, "pitch", 0) + pagePitch)).rotateY(rad(number(t, "yaw", 0) + pageYaw));
            boolean fit = !Boolean.FALSE.equals(t.get("fit"));
            int light = t.get("light") instanceof Number n ? LightCoordsUtil.pack(Math.max(0, Math.min(15, n.intValue())), Math.max(0, Math.min(15, n.intValue()))) : LightCoordsUtil.FULL_BRIGHT;
            // One lighting per slot, as for a Java bind (the slot is flushed once): the last thing drawn decides.
            Lighting.Entry lighting = LIGHTING.get(String.valueOf(t.get("lighting")));
            ItemStack stack = stack(mc, t);
            if (stack != null) {
                if (!stack.isEmpty()) item(mc, stack, fit ? Math.min(width, height) : 1, turn, light, lighting, pose, nodes);
            } else if (t.get("block") instanceof String id) {
                block(mc, id, t, fit ? Math.min(width, height) * 0.625f : 1, turn, light, lighting, pose, nodes);
            } else if (t.get("text") != null) {
                text(mc, String.valueOf(t.get("text")), t, turn, light, pose, nodes);
            } else if (t.get("image") instanceof String texture) {
                float w = number(t, "w", Math.min(width, height)), h = number(t, "h", Math.min(width, height));
                pose.rotate(turn);
                quad(RenderTypes.text(ServerApi.id(texture)), t, w, h, light, pose, nodes);
            } else if (t.get("rect") != null) {
                pose.rotate(turn);
                quad(RenderTypes.debugQuads(), t, number(t, "w", width), number(t, "h", height), light, pose, nodes);
            } else {
                Entity entity = entity(mc, t);
                if (entity != null) entity(mc, entity, t, fit ? width : 0, height, turn, light, lighting, pose, nodes);
            }
            pose.popPose();
        }
    }

    /**
     * The thing's own moves, in slot pixels, in this order: {@code transform} (a list of {@code {'translate', x, y, z}},
     * {@code {'rotate', 'x'|'y'|'z', degrees}} and {@code {'scale', s}} / {@code {'scale', x, y, z}}, applied as a
     * PoseStack would), then {@code x, y, z}, then {@code scale} (a number or {@code {x, y, z}}).
     */
    private static void transform(Map<String, Object> t, PoseStack pose) {
        if (t.get("transform") instanceof List<?> ops) {
            int n = 0;
            for (Object o : ops) {
                if (!(o instanceof List<?> op) || op.isEmpty() || ++n > MAX_OPS) continue;
                switch (String.valueOf(op.get(0))) {
                    case "translate" -> pose.translate(at(op, 1, 0), at(op, 2, 0), at(op, 3, 0));
                    case "scale" -> {
                        float s = at(op, 1, 1);
                        pose.scale(s, at(op, 2, s), at(op, 3, s));
                    }
                    case "rotate" -> {
                        float angle = rad(at(op, 2, 0));
                        switch (String.valueOf(op.size() > 1 ? op.get(1) : "")) {
                            case "x" -> pose.rotate(new Quaternionf().rotateX(angle));
                            case "y" -> pose.rotate(new Quaternionf().rotateY(angle));
                            case "z" -> pose.rotate(new Quaternionf().rotateZ(angle));
                            default -> {}
                        }
                    }
                    default -> {}
                }
            }
        }
        pose.translate(number(t, "x", 0), number(t, "y", 0), number(t, "z", 0));
        if (t.get("scale") instanceof List<?> s) pose.scale(size(at(s, 0, 1)), size(at(s, 1, 1)), size(at(s, 2, 1)));
        else {
            float s = size(number(t, "scale", 1));
            pose.scale(s, s, s);
        }
    }

    private static float size(float s) {
        return Math.max(0.001f, Math.min(4096, Math.abs(s))) * Math.signum(s == 0 ? 1 : s);
    }

    // ---- items

    /** The stack of an {@code item} or {@code inventory} thing; null if it is neither. */
    private static ItemStack stack(Minecraft mc, Map<String, Object> t) {
        if (t.get("inventory") instanceof Number n) {
            var inventory = mc.player.getInventory();
            int i = n.intValue();
            return i >= 0 && i < inventory.getContainerSize() ? inventory.getItem(i) : ItemStack.EMPTY;
        }
        if (!(t.get("item") instanceof String id)) return null;
        Item item = item(id);
        if (item == null) return ItemStack.EMPTY;
        // ponytail: a new stack per frame; cache by description if slots with details ever show up in a profile.
        ItemStack stack = new ItemStack(item);
        if (Boolean.TRUE.equals(t.get("enchanted")) || t.get("enchantments") != null) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Any model the game has loaded (a mod's, or one in the resource's assets), as the item_model component does.
        if (t.get("item_model") instanceof String model && Identifier.tryParse(model) != null) stack.set(DataComponents.ITEM_MODEL, Identifier.tryParse(model));
        if (t.get("damage") != null || t.get("model") != null) {
            Map<String, Object> details = new LinkedHashMap<>(); // the looks only: enchantments need the server's registries
            details.put("damage", stack.isDamageableItem() ? t.get("damage") : null);
            details.put("model", t.get("model"));
            ItemCompat.apply(stack, details, null);
        }
        return stack;
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    private static void item(Minecraft mc, ItemStack stack, float size, Quaternionf turn, int light, Lighting.Entry lighting, PoseStack pose, SubmitNodeCollector nodes) {
        mc.getItemModelResolver().updateForTopItem(ITEM, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
        // As GuiItemAtlas.drawToSlot: the model fills a unit square, y up (the slot's pose has z flipped already).
        pose.scale(size, -size, -size);
        pose.rotate(turn);
        mc.gameRenderer.lighting().setupFor(lighting != null ? lighting : ITEM.usesBlockLight() ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
        ITEM.submit(pose, nodes, light, OverlayTexture.NO_OVERLAY, 0);
    }

    // ---- blocks

    /** A block as the inventory shows block items (seen from above and the side), with {@code state = { lit = true, facing = 'north' }}. */
    private static void block(Minecraft mc, String id, Map<String, Object> t, float size, Quaternionf turn, int light, Lighting.Entry lighting, PoseStack pose, SubmitNodeCollector nodes) {
        Identifier key = Identifier.tryParse(id);
        Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null) return;
        BlockState state = block.defaultBlockState();
        if (t.get("state") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> e : properties.entrySet()) {
                Property<?> property = block.getStateDefinition().getProperty(String.valueOf(e.getKey()));
                if (property != null) state = with(state, property, String.valueOf(e.getValue()));
            }
        }
        if (blocks == null) blocks = new BlockModelResolver(mc.getModelManager());
        BlockModelRenderState model = new BlockModelRenderState();
        blocks.update(model, state, BLOCK_CONTEXT);
        pose.scale(size, -size, -size);
        pose.rotate(new Quaternionf().rotateX(rad(30)).rotateY(rad(225)));
        pose.rotate(turn);
        pose.translate(-0.5f, -0.5f, -0.5f);
        mc.gameRenderer.lighting().setupFor(lighting != null ? lighting : Lighting.Entry.ITEMS_3D);
        model.submit(pose, nodes, light, OverlayTexture.NO_OVERLAY, 0);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    // ---- text and flat shapes

    /** Text in the game's font, centred, 9 pixels tall at scale 1: {@code color}, {@code shadow}, {@code background}. */
    private static void text(Minecraft mc, String text, Map<String, Object> t, Quaternionf turn, int light, PoseStack pose, SubmitNodeCollector nodes) {
        if (text.length() > 256) text = text.substring(0, 256);
        FormattedCharSequence line = FormattedCharSequence.forward(text, Style.EMPTY);
        pose.rotate(turn);
        nodes.submitText(pose, -mc.font.width(line) / 2f, -mc.font.lineHeight / 2f, line, Boolean.TRUE.equals(t.get("shadow")), Font.DisplayMode.NORMAL,
                light, ClientHud.color(t, 0xFFFFFF), t.get("background") instanceof Number n ? (int) n.longValue() : 0, 0);
    }

    /** A flat {@code w} x {@code h} rectangle at the centre: an image ({@code u0, v0, u1, v1} of it, tinted by {@code color}) or a colour. */
    private static void quad(net.minecraft.client.renderer.rendertype.RenderType type, Map<String, Object> t, float w, float h, int light, PoseStack pose, SubmitNodeCollector nodes) {
        boolean image = t.get("image") != null;
        int c = image ? (t.get("color") == null ? 0xFFFFFFFF : ClientHud.color(t, 0xFFFFFF)) : ClientHud.color(t, 0xFFFFFF);
        float u0 = number(t, "u0", 0), v0 = number(t, "v0", 0), u1 = number(t, "u1", 1), v1 = number(t, "v1", 1);
        float[][] corners = {{-w / 2, -h / 2, u0, v0}, {-w / 2, h / 2, u0, v1}, {w / 2, h / 2, u1, v1}, {w / 2, -h / 2, u1, v0}};
        nodes.submitCustomGeometry(pose, type, (last, vc) -> {
            for (float[] p : corners) {
                var v = vc.addVertex(last, p[0], p[1], 0).setColor(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF, c >>> 24);
                if (image) v.setUv(p[2], p[3]).setLight(light);
            }
        });
    }

    // ---- entities

    /** The entity of a {@code player}, {@code entity} (alive, by id) or {@code entity_type} (a stand-in) thing, or null. */
    private static Entity entity(Minecraft mc, Map<String, Object> t) {
        if (Boolean.TRUE.equals(t.get("player"))) return mc.player;
        if (t.get("entity") instanceof String id) {
            try {
                return mc.level.getEntity(UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (!(t.get("entity_type") instanceof String id)) return null;
        Identifier key = Identifier.tryParse(id);
        EntityType<?> type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        if (type == null) return null;
        if (standinsOf != mc.level) {
            STANDINS.clear();
            standinsOf = mc.level;
        }
        if (!STANDINS.containsKey(type)) {
            Entity made = null;
            try {
                made = type.create(mc.level, EntitySpawnReason.LOAD);
                if (made != null) made.setId(-1000 - STANDINS.size()); // renderers ask for one; it is in no world, so any will do
            } catch (RuntimeException e) {
                Troupe.LOG.warn("Can't make a '{}' to show in a slot", id, e);
            }
            STANDINS.put(type, made);
        }
        Entity standin = STANDINS.get(type);
        if (standin instanceof LivingEntity living) { // shared by every slot of this type: dress it for this one
            Map<?, ?> equipment = t.get("equipment") instanceof Map<?, ?> m ? m : Map.of();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                Item worn = equipment.get(slot.getName()) instanceof String s ? item(s) : null;
                living.setItemSlot(slot, worn == null ? ItemStack.EMPTY : new ItemStack(worn));
            }
            if (living instanceof Mob mob) mob.setBaby(Boolean.TRUE.equals(t.get("baby")));
        }
        return standin;
    }

    /**
     * Standing at the centre of the slot, facing out. With {@code width} > 0 it is fitted to the slot as the inventory
     * fits the player; with 0 ({@code fit = false}) one block is one pixel, for {@code scale} to decide.
     */
    private static void entity(Minecraft mc, Entity entity, Map<String, Object> t, int width, int height, Quaternionf turn, int light, Lighting.Entry lighting, PoseStack pose, SubmitNodeCollector nodes) {
        var dispatcher = mc.getEntityRenderDispatcher();
        try {
            var state = dispatcher.getRenderer(entity).createRenderState(entity, 1f);
            state.shadowPieces.clear();
            state.outlineColor = 0;
            state.nameTag = null;
            state.scoreText = null;
            state.lightCoords = light; // wherever it is in the world, it is lit as asked
            pose(t, state);
            float w = Math.max(state.boundingBoxWidth, 0.1f), h = Math.max(state.boundingBoxHeight, 0.1f);
            float fit = width > 0 ? 0.85f * Math.min(width / w, height / h) : 1;
            pose.scale(fit, fit, fit);
            pose.rotate(new Quaternionf().rotateZ((float) Math.PI).mul(turn));
            pose.translate(0, -h / 2, 0); // turned about its middle, not its feet
            mc.gameRenderer.lighting().setupFor(lighting != null ? lighting : Lighting.Entry.ENTITY_IN_UI);
            dispatcher.submit(state, new CameraRenderState(), 0, 0, 0, pose, nodes);
        } catch (RuntimeException e) { // a stand-in is not a real entity: some renderers want more than it has
            if (t.get("entity_type") == null) throw e;
            Troupe.LOG.warn("Can't draw a '{}' in a slot", t.get("entity_type"), e);
            STANDINS.put(entity.getType(), null);
        }
    }

    /**
     * How the entity stands, on top of what it is doing: {@code body_yaw}, {@code head_yaw}, {@code head_pitch} (degrees;
     * {@code natural = true} keeps the ones it has in the world), {@code walk} and {@code walk_speed} (the legs),
     * {@code swing} (0..1, the arm), {@code pose} (a {@code Pose}: 'crouching', 'sleeping', 'swimming'...), {@code crouching},
     * {@code upside_down}, {@code hurt}, {@code fire}, {@code age} (ticks, for idle animations).
     */
    private static void pose(Map<String, Object> t, EntityRenderState state) {
        if (t.get("age") instanceof Number n) state.ageInTicks = n.floatValue();
        if (t.get("fire") instanceof Boolean b) state.displayFireAnimation = b;
        if (!(state instanceof LivingEntityRenderState living)) return;
        if (!Boolean.TRUE.equals(t.get("natural"))) {
            living.bodyRot = 180;
            living.yRot = living.xRot = 0;
        }
        if (t.get("body_yaw") instanceof Number n) living.bodyRot = 180 + n.floatValue();
        if (t.get("head_yaw") instanceof Number n) living.yRot = n.floatValue();
        if (t.get("head_pitch") instanceof Number n) living.xRot = n.floatValue();
        if (t.get("walk") instanceof Number n) {
            living.walkAnimationPos = n.floatValue();
            living.walkAnimationSpeed = number(t, "walk_speed", 1);
        }
        if (t.get("upside_down") instanceof Boolean b) living.isUpsideDown = b;
        if (t.get("hurt") instanceof Boolean b) living.hasRedOverlay = b;
        if (t.get("pose") instanceof String name) {
            try {
                living.pose = Pose.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // not a pose: as it is
            }
        }
        if (living instanceof HumanoidRenderState humanoid && t.get("crouching") instanceof Boolean b) humanoid.isCrouching = b;
        if (living instanceof ArmedEntityRenderState armed && t.get("swing") instanceof Number n) armed.swingAnimation = n.floatValue();
    }

    // ---- options

    private static float number(Map<String, Object> t, String key, float fallback) {
        return t.get(key) instanceof Number n && Float.isFinite(n.floatValue()) ? n.floatValue() : fallback;
    }

    private static float at(List<?> list, int i, float fallback) {
        return i < list.size() && list.get(i) instanceof Number n && Float.isFinite(n.floatValue()) ? n.floatValue() : fallback;
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
//?}

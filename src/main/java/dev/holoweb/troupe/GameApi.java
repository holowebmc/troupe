package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.lua.EntityRef;
import dev.holoweb.troupe.lua.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The second batch of the server game API (the prelude adds the method forms):
 * <pre>
 * player:health() :set_health(n) :max_health()   player:food() :set_food(n)   player:gamemode() :set_gamemode('creative')
 * player:xp() → {level, progress}  :give_xp(points)  :set_level(n)
 * player:effect('minecraft:speed', seconds, amplifier?, { particles = true })  :effects()  :clear_effects(id?)
 * player:title(text, subtitle?, { fade_in, stay, fade_out } seconds)  :actionbar(text)  :sound(id, { volume, pitch })
 * player:kick(reason?)
 * player:items() → {slot, id, count, name}…  :item(slot)  :set_item(slot, id|nil, count?)  :count(id)  :take(id, n?)  :held()
 * entities.spawn(type, x, y, z, dim?)  entities.near(x, y, z, radius, dim?, { type })  entities.get(id)
 *   entity.id  entity.type  entity:pos() :teleport(x, y, z, dim?) :remove() :alive() :name() :set_name(text|nil) :health()
 * world.time(dim?)  world.set_time(ticks, dim?)  world.weather() → 'clear'|'rain'|'thunder'  world.set_weather(kind, seconds?)
 * world.sound(id, x, y, z, { volume, pitch, dim })  world.particles(id, x, y, z, { count, spread, speed, dim })
 * </pre>
 * Loaded chunks only, like {@code world.block}: nothing here loads or generates terrain. Durations are seconds; the
 * time of day is ticks (0 sunrise, 6000 noon, 12000 sunset, 18000 midnight).
 */
final class GameApi {
    private static final int MAX_ENTITIES = 256, MAX_RADIUS = 128, MAX_PARTICLES = 1000, MAX_FILL = 32768;

    private final ServerApi api;

    GameApi(ModuleRegistry modules, ServerApi api) {
        this.api = api;
        players(modules);
        inventory(modules);
        entities(modules);
        world(modules);
        blocks(modules);
        advancedEntities(modules);
        customItems(modules);
        models(modules);
    }

    static final String MODEL = "troupe:model", ANIM = "troupe:anim";

    /**
     * {@code entity:set_model('vendedor' | 'outra:vendedor' | nil, { scale = 1, idle, walk })} and
     * {@code entity:animate(name | nil)}, players too: clients that see the entity draw that model from the resource's
     * {@code models/} instead of it (the hitbox stays the entity's). {@code idle} / {@code walk} play by themselves
     * while it stands or moves; {@code animate} plays one over them until it ends (or for good, when it loops).
     * Both go as entity state, so only who sees the entity gets them.
     */
    private void models(ModuleRegistry modules) {
        var host = api.host();
        both(modules, "set_model", (e, a) -> {
            String id = e.getStringUUID();
            if (a.get(1) == null) {
                host.setEntityState(a.resource(), id, MODEL, null);
                host.setEntityState(a.resource(), id, ANIM, null);
                return null;
            }
            String ref = BbModel.qualify(a.resource(), a.string(1));
            BbModel m = BbModel.load(host, ref);
            Map<String, Object> o = a.get(2) == null ? Map.of() : a.table(2);
            double scale = o.get("scale") instanceof Number n ? n.doubleValue() : 1;
            if (!(scale > 0) || scale > 16) throw new IllegalArgumentException("scale must be 0..16");
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            j.addProperty("model", ref);
            j.addProperty("scale", scale);
            for (String k : List.of("idle", "walk")) {
                if (o.get(k) == null) continue;
                j.addProperty(k, m.animation(String.valueOf(o.get(k)), ref).name());
            }
            host.setEntityState(a.resource(), id, MODEL, j.toString());
            return null;
        });
        both(modules, "animate", (e, a) -> {
            String id = e.getStringUUID();
            if (a.get(1) == null) {
                host.setEntityState(a.resource(), id, ANIM, null);
                return null;
            }
            String model = host.getEntityState(a.resource(), id, MODEL);
            if (model == null) throw new IllegalArgumentException("set_model first");
            String ref = com.google.gson.JsonParser.parseString(model).getAsJsonObject().get("model").getAsString();
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            j.addProperty("name", BbModel.load(host, ref).animation(a.string(1), ref).name());
            j.addProperty("tick", e.level().getGameTime()); // clients count from here: all in step, late viewers join mid-way
            host.setEntityState(a.resource(), id, ANIM, j.toString());
            return null;
        });
    }

    /** Functions for both entities.* and players.* (the first argument is an entity id or a player id). */
    private interface OnEntity {
        Object call(Entity e, Args a);
    }

    private void both(ModuleRegistry modules, String fn, OnEntity f) {
        modules.global("entities").function(fn, a -> f.call(entity(a), a));
        modules.global("players").function(fn, a -> f.call(api.player(a), a));
    }

    private void advancedEntities(ModuleRegistry modules) {
        // player:set_instance('apt_12') / entity:set_instance(nil): see Instances.
        both(modules, "set_instance", (e, a) -> {
            String name = a.get(1) == null ? null : a.string(1);
            if (name != null && (name.isBlank() || name.length() > 64)) throw new IllegalArgumentException("instance names have 1..64 characters");
            Instances.set(e, name, a.resource());
            return null;
        });
        both(modules, "instance", (e, a) -> Instances.get(e, a.resource()));
        both(modules, "attribute", (e, a) -> {
            var i = Compat.attribute(living(e), attribute(a.string(1)));
            return i == null ? null : i.getValue();
        });
        both(modules, "set_attribute", (e, a) -> {
            var i = Compat.attribute(living(e), attribute(a.string(1)));
            if (i == null) throw new IllegalArgumentException("this entity has no " + a.string(1));
            i.setBaseValue(a.number(2));
            return null;
        });
        both(modules, "set_velocity", (e, a) -> {
            e.setDeltaMovement(a.number(1), a.number(2), a.number(3));
            Compat.syncMotion(e); // sends the new motion to clients (and to the player, for players)
            return null;
        });
        both(modules, "glow", (e, a) -> {
            e.setGlowingTag(a.optBool(1, true));
            return null;
        });
        both(modules, "mount", (e, a) -> {
            Entity vehicle = find(a.string(1));
            if (vehicle == null) throw new IllegalArgumentException("vehicle isn't loaded");
            return e.startRiding(vehicle);
        });
        both(modules, "dismount", (e, a) -> {
            e.stopRiding();
            return null;
        });
        both(modules, "vehicle", (e, a) -> e.getVehicle() == null ? null : who(e.getVehicle()));
        both(modules, "passengers", (e, a) -> e.getPassengers().stream().map(GameApi::who).toList());

        modules.global("entities")
                .function("set_health", a -> {
                    LivingEntity l = living(entity(a));
                    l.setHealth((float) Math.max(0, Math.min(l.getMaxHealth(), a.number(1))));
                    return null;
                })
                .function("equip", a -> {
                    living(entity(a)).setItemSlot(slot(a.string(1)), a.get(2) == null ? ItemStack.EMPTY : stack(a.get(2), a.get(3), false));
                    return null;
                })
                .function("equipment", a -> {
                    LivingEntity l = living(entity(a));
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (String s : SLOTS) {
                        Map<String, Object> item = item(l.getItemBySlot(slot(s)));
                        if (item != null) out.put(s, item);
                    }
                    return out;
                })
                .function("effect", a -> {
                    MobEffect effect = effect(a.string(1));
                    double seconds = a.number(2);
                    if (!(seconds > 0) || seconds > 1_000_000) throw new IllegalArgumentException("seconds must be > 0");
                    long amplifier = a.get(3) == null ? 0 : a.integer(3);
                    if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("amplifier must be 0..255");
                    return living(entity(a)).addEffect(Compat.effect(effect, (int) Math.round(seconds * 20), (int) amplifier, true));
                })
                .function("clear_effects", a -> {
                    LivingEntity l = living(entity(a));
                    if (a.get(1) == null) l.removeAllEffects();
                    else Compat.removeEffect(l, effect(a.string(1)));
                    return null;
                })
                .function("move_to", a -> {
                    if (!(entity(a) instanceof Mob mob)) throw new IllegalArgumentException("only mobs walk to places");
                    double speed = a.optNumber(4, 1);
                    if (!(speed > 0) || speed > 4) throw new IllegalArgumentException("speed must be 0..4");
                    return mob.getNavigation().moveTo(a.number(1), a.number(2), a.number(3), speed);
                })
                .function("set_ai", a -> {
                    if (!(entity(a) instanceof Mob mob)) throw new IllegalArgumentException("only mobs have AI");
                    mob.setNoAi(!a.bool(1));
                    return null;
                });
    }

    private static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "mainhand", "offhand");

    private static EquipmentSlot slot(String name) {
        if (!SLOTS.contains(name)) throw new IllegalArgumentException("slot is one of " + String.join(", ", SLOTS));
        return EquipmentSlot.valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }

    private static LivingEntity living(Entity e) {
        if (!(e instanceof LivingEntity l)) throw new IllegalArgumentException("not a living entity");
        return l;
    }

    private static net.minecraft.world.entity.ai.attributes.Attribute attribute(String id) {
        return BuiltInRegistries.ATTRIBUTE.getOptional(ServerApi.id(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown attribute '" + id + "'"));
    }

    /** A player as a player handle, anything else as an entity handle. */
    static Object who(Entity e) {
        return e instanceof ServerPlayer p ? ServerApi.peer(p) : handle(e);
    }

    private void blocks(ModuleRegistry modules) {
        modules.global("world")
                .function("block_state", a -> {
                    ServerLevel level = api.level(a, 3);
                    BlockPos pos = BlockPos.containing(a.number(0), a.number(1), a.number(2));
                    if (!level.isLoaded(pos)) return null;
                    BlockState s = level.getBlockState(pos);
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (Property<?> p : s.getProperties()) props.put(p.getName(), valueName(s, p));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                    out.put("props", props);
                    return out;
                })
                .function("fill", a -> {
                    Block block = BuiltInRegistries.BLOCK.getOptional(ServerApi.id(a.string(6)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown block '" + a.string(6) + "'"));
                    ServerLevel level = api.level(a, 7);
                    BlockState state = state(block, a.get(8) == null ? Map.of() : a.table(8));
                    BlockPos from = BlockPos.containing(a.number(0), a.number(1), a.number(2));
                    BlockPos to = BlockPos.containing(a.number(3), a.number(4), a.number(5));
                    long volume = (long) (Math.abs(from.getX() - to.getX()) + 1) * (Math.abs(from.getY() - to.getY()) + 1) * (Math.abs(from.getZ() - to.getZ()) + 1);
                    if (volume > MAX_FILL) throw new IllegalArgumentException("too many blocks (" + volume + ", max " + MAX_FILL + ")");
                    for (BlockPos p : BlockPos.betweenClosed(from, to)) if (!level.isLoaded(p)) throw new IllegalArgumentException("part of the region isn't loaded");
                    long changed = 0;
                    for (BlockPos p : BlockPos.betweenClosed(from, to)) if (level.setBlock(p, state, Block.UPDATE_ALL)) changed++;
                    return changed;
                })
                .function("container", a -> {
                    Container c = container(a, 3);
                    if (c == null) return null;
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        Map<String, Object> item = item(c.getItem(i));
                        if (item == null) continue;
                        item.put("slot", i);
                        out.add(item);
                    }
                    return out;
                })
                .function("container_size", a -> {
                    Container c = container(a, 3);
                    return c == null ? null : c.getContainerSize();
                })
                .function("set_container_item", a -> {
                    Container c = container(a, 6);
                    if (c == null) throw new IllegalArgumentException("no container there");
                    long slot = a.integer(3);
                    if (slot < 0 || slot >= c.getContainerSize()) throw new IllegalArgumentException("slot must be 0.." + (c.getContainerSize() - 1));
                    c.setItem((int) slot, a.get(4) == null ? ItemStack.EMPTY : stack(a.get(4), a.get(5), false));
                    c.setChanged();
                    return null;
                });

        modules.global("players").function("looking_at", a -> {
            ServerPlayer p = api.player(a);
            double reach = a.optNumber(1, 5);
            if (!(reach > 0) || reach > 64) throw new IllegalArgumentException("distance must be 0..64");
            if (!(p.pick(reach, 1, false) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
            BlockPos pos = hit.getBlockPos();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("x", pos.getX());
            out.put("y", pos.getY());
            out.put("z", pos.getZ());
            out.put("id", BuiltInRegistries.BLOCK.getKey(Compat.level(p).getBlockState(pos).getBlock()).toString());
            out.put("face", hit.getDirection().getName());
            return out;
        });
    }

    /** The block's default state with {@code props} ({@code {facing = 'north', lit = true}}) applied. */
    static BlockState state(Block block, Map<String, Object> props) {
        BlockState s = block.defaultBlockState();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Property<?> p = block.getStateDefinition().getProperty(e.getKey());
            if (p == null) throw new IllegalArgumentException(BuiltInRegistries.BLOCK.getKey(block) + " has no property '" + e.getKey() + "'");
            Object v = e.getValue();
            if (v instanceof Double d && d == Math.rint(d)) v = d.longValue(); // Lua numbers: 3.0 → "3"
            s = with(s, p, String.valueOf(v));
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        T v = p.getValue(value).orElseThrow(() -> new IllegalArgumentException("'" + value + "' isn't a value of " + p.getName()));
        return s.setValue(p, v);
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    private Container container(Args a, int dimArg) {
        ServerLevel level = api.level(a, dimArg);
        BlockPos pos = BlockPos.containing(a.number(0), a.number(1), a.number(2));
        if (!level.isLoaded(pos)) return null;
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    /**
     * An item from a script: an id, or {@code {id, count, name, lore, enchantments, damage, model}}. {@code count}
     * (the argument) is used when the table has none; {@code anyCount} allows more than a stack (give splits it).
     */
    ItemStack stack(Object spec, Object count, boolean anyCount) {
        String id;
        Map<String, Object> details = null;
        if (spec instanceof String s) {
            id = s;
        } else if (spec instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) m;
            details = d;
            if (!(d.get("id") instanceof String s)) throw new IllegalArgumentException("item table needs an id");
            id = s;
            if (d.get("count") != null) count = d.get("count");
        } else {
            throw new IllegalArgumentException("item expected (an id or { id = ... })");
        }
        Custom own = custom.get(id);
        Item item = item(own != null ? String.valueOf(own.spec().get("base")) : id);
        if (count != null && !(count instanceof Number)) throw new IllegalArgumentException("count must be a number");
        long n = count == null ? 1 : ((Number) count).longValue();
        int max = anyCount ? 64 * 36 : new ItemStack(item).getMaxStackSize();
        if (n < 1 || n > max) throw new IllegalArgumentException("count must be 1.." + max);
        ItemStack s = new ItemStack(item, (int) n);
        if (own != null) {
            ItemCompat.apply(s, own.spec(), api.server());
            ItemCompat.setCustomId(s, id);
        }
        if (details != null) ItemCompat.apply(s, details, api.server());
        return s;
    }

    // ---- items a resource defines: a vanilla item dressed up (name, model...) and tagged with the resource's id

    private record Custom(String resource, Map<String, Object> spec) {}

    private static final java.util.regex.Pattern ITEM_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final Map<String, Custom> custom = new java.util.HashMap<>();

    /**
     * {@code items.define('banco:cartao', { base = 'minecraft:paper', name, lore, model, item_model, glint, enchantments })}:
     * from then on that id works wherever an item id does (give, take, count, set_item, containers), and items read
     * back with it ({@code id = 'banco:cartao', base = 'minecraft:paper'}). Nothing is registered in the game: clients
     * without the resource's assets see the base item.
     */
    private void customItems(ModuleRegistry modules) {
        modules.global("items")
                .function("define", a -> {
                    String id = a.string(0);
                    Map<String, Object> spec = a.table(1);
                    if (!ITEM_ID.matcher(id).matches()) throw new IllegalArgumentException("item ids look like 'banco:cartao'");
                    if (BuiltInRegistries.ITEM.containsKey(ServerApi.id(id))) throw new IllegalArgumentException("'" + id + "' is already an item of the game");
                    Custom existing = custom.get(id);
                    if (existing != null && !existing.resource().equals(a.resource())) throw new IllegalArgumentException("'" + id + "' belongs to " + existing.resource());
                    if (!(spec.get("base") instanceof String base)) throw new IllegalArgumentException("base must be an item id, e.g. 'minecraft:paper'");
                    ItemCompat.apply(new ItemStack(item(base)), spec, api.server()); // fail now on a bad definition
                    custom.put(id, new Custom(a.resource(), spec));
                    return null;
                })
                .function("defined", a -> custom.keySet().stream().sorted().toList());
    }

    void forget(String resource) {
        Instances.forget(resource, api.serverOrNull());
        custom.values().removeIf(c -> c.resource().equals(resource));
    }

    /** What {@code count} and {@code take} look for: a defined item by its tag, a game item only when it has none. */
    private java.util.function.Predicate<ItemStack> matching(String id) {
        if (custom.containsKey(id)) return s -> id.equals(ItemCompat.customId(s));
        Item item = item(id);
        return s -> s.is(item) && ItemCompat.customId(s) == null;
    }

    private void players(ModuleRegistry modules) {
        modules.global("players")
                .function("health", a -> (double) api.player(a).getHealth())
                .function("max_health", a -> (double) api.player(a).getMaxHealth())
                .function("set_health", a -> {
                    ServerPlayer p = api.player(a);
                    p.setHealth((float) Math.max(0, Math.min(p.getMaxHealth(), a.number(1))));
                    return null;
                })
                .function("food", a -> api.player(a).getFoodData().getFoodLevel())
                .function("set_food", a -> {
                    api.player(a).getFoodData().setFoodLevel((int) Math.max(0, Math.min(20, a.integer(1))));
                    return null;
                })
                .function("gamemode", a -> api.player(a).gameMode.getGameModeForPlayer().getName())
                .function("set_gamemode", a -> {
                    GameType mode = GameType.byName(a.string(1), null);
                    if (mode == null) throw new IllegalArgumentException("unknown game mode '" + a.string(1) + "' (survival, creative, adventure, spectator)");
                    return api.player(a).setGameMode(mode);
                })
                .function("xp", a -> {
                    ServerPlayer p = api.player(a);
                    Map<String, Object> xp = new LinkedHashMap<>();
                    xp.put("level", p.experienceLevel);
                    xp.put("progress", (double) p.experienceProgress);
                    return xp;
                })
                .function("give_xp", a -> {
                    api.player(a).giveExperiencePoints((int) a.integer(1));
                    return null;
                })
                .function("set_level", a -> {
                    long level = a.integer(1);
                    if (level < 0 || level > 1_000_000) throw new IllegalArgumentException("level must be 0..1000000");
                    api.player(a).setExperienceLevels((int) level);
                    return null;
                })
                .function("effect", a -> {
                    MobEffect effect = effect(a.string(1));
                    double seconds = a.number(2);
                    if (!(seconds > 0) || seconds > 1_000_000) throw new IllegalArgumentException("seconds must be > 0");
                    long amplifier = a.get(3) == null ? 0 : a.integer(3);
                    if (amplifier < 0 || amplifier > 255) throw new IllegalArgumentException("amplifier must be 0..255");
                    boolean particles = a.get(4) == null || !Boolean.FALSE.equals(a.table(4).get("particles"));
                    return api.player(a).addEffect(Compat.effect(effect, (int) Math.round(seconds * 20), (int) amplifier, particles));
                })
                .function("effects", a -> {
                    List<Object> out = new ArrayList<>();
                    for (MobEffectInstance e : api.player(a).getActiveEffects()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", effectId(e));
                        m.put("seconds", e.getDuration() / 20.0);
                        m.put("amplifier", e.getAmplifier());
                        out.add(m);
                    }
                    return out;
                })
                .function("clear_effects", a -> {
                    ServerPlayer p = api.player(a);
                    if (a.get(1) == null) p.removeAllEffects();
                    else Compat.removeEffect(p, effect(a.string(1)));
                    return null;
                })
                .function("title", a -> {
                    ServerPlayer p = api.player(a);
                    Map<String, Object> o = a.get(3) == null ? Map.of() : a.table(3);
                    p.connection.send(new ClientboundSetTitlesAnimationPacket(ticks(o, "fade_in", 0.5), ticks(o, "stay", 3.5), ticks(o, "fade_out", 1)));
                    p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(a.optString(2, ""))));
                    p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(a.string(1))));
                    return null;
                })
                .function("actionbar", a -> {
                    api.player(a).connection.send(new ClientboundSetActionBarTextPacket(Component.literal(a.string(1))));
                    return null;
                })
                .function("sound", a -> {
                    ServerPlayer p = api.player(a);
                    Map<String, Object> o = a.get(2) == null ? Map.of() : a.table(2);
                    p.connection.send(sound(a.string(1), p.getX(), p.getY(), p.getZ(), number(o, "volume", 1), number(o, "pitch", 1)));
                    return null;
                })
                .function("kick", a -> {
                    api.player(a).connection.disconnect(Component.literal(a.optString(1, "Kicked")));
                    return null;
                });
    }

    private void inventory(ModuleRegistry modules) {
        modules.global("players")
                .function("items", a -> {
                    Inventory inv = api.player(a).getInventory();
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        Map<String, Object> item = item(inv.getItem(i));
                        if (item == null) continue;
                        item.put("slot", i);
                        out.add(item);
                    }
                    return out;
                })
                .function("item", a -> item(api.player(a).getInventory().getItem(slot(api.player(a), a.integer(1)))))
                .function("set_item", a -> {
                    ServerPlayer p = api.player(a);
                    int slot = slot(p, a.integer(1));
                    p.getInventory().setItem(slot, a.get(2) == null ? ItemStack.EMPTY : stack(a.get(2), a.get(3), false));
                    return null;
                })
                .function("count", a -> {
                    Inventory inv = api.player(a).getInventory();
                    var wanted = matching(a.string(1));
                    long n = 0;
                    for (int i = 0; i < inv.getContainerSize(); i++) if (wanted.test(inv.getItem(i))) n += inv.getItem(i).getCount();
                    return n;
                })
                .function("take", a -> {
                    Inventory inv = api.player(a).getInventory();
                    var wanted = matching(a.string(1));
                    long want = a.get(2) == null ? 1 : a.integer(2);
                    if (want < 1) throw new IllegalArgumentException("count must be >= 1");
                    long taken = 0;
                    for (int i = 0; i < inv.getContainerSize() && taken < want; i++) {
                        ItemStack s = inv.getItem(i);
                        if (!wanted.test(s)) continue;
                        int n = (int) Math.min(s.getCount(), want - taken);
                        s.shrink(n);
                        if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                        taken += n;
                    }
                    inv.setChanged();
                    return taken;
                })
                .function("held", a -> item(api.player(a).getMainHandItem()));
    }

    private void entities(ModuleRegistry modules) {
        modules.global("entities")
                .function("spawn", a -> {
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ServerApi.id(a.string(0)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown entity type '" + a.string(0) + "'"));
                    if (!type.canSummon()) throw new IllegalArgumentException("'" + a.string(0) + "' can't be spawned");
                    ServerLevel level = api.level(a, 4);
                    double x = a.number(1), y = a.number(2), z = a.number(3);
                    BlockPos pos = BlockPos.containing(x, y, z);
                    if (!level.isLoaded(pos)) throw new IllegalArgumentException("that chunk isn't loaded");
                    Entity e = Compat.spawn(type, level, pos);
                    if (e == null) return null;
                    Compat.teleport(e, level, x, y, z);
                    return handle(e);
                })
                .function("near", a -> {
                    ServerLevel level = api.level(a, 4);
                    double r = a.number(3);
                    if (!(r > 0) || r > MAX_RADIUS) throw new IllegalArgumentException("radius must be 0.." + MAX_RADIUS);
                    Vec3 c = new Vec3(a.number(0), a.number(1), a.number(2));
                    String type = a.get(5) == null ? null : (String) a.table(5).get("type");
                    List<Object> out = new ArrayList<>();
                    for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(r), e -> !(e instanceof Player) && e.position().distanceTo(c) <= r)) {
                        if (type != null && !typeId(e).equals(type)) continue;
                        out.add(handle(e));
                        if (out.size() == MAX_ENTITIES) break;
                    }
                    return out;
                })
                .function("get", a -> {
                    Entity e = find(a.string(0));
                    return e == null || e instanceof Player ? null : handle(e);
                })
                .function("alive", a -> {
                    Entity e = find(a.string(0));
                    return e != null && e.isAlive();
                })
                .function("type", a -> typeId(entity(a)))
                .function("pos", a -> {
                    Entity e = entity(a);
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", e.getX());
                    pos.put("y", e.getY());
                    pos.put("z", e.getZ());
                    pos.put("dim", Compat.dimension((ServerLevel) e.level()));
                    return pos;
                })
                .function("teleport", a -> {
                    Entity e = entity(a);
                    ServerLevel level = a.get(4) == null ? (ServerLevel) e.level() : api.level(a.string(4));
                    if (!level.isLoaded(BlockPos.containing(a.number(1), a.number(2), a.number(3)))) throw new IllegalArgumentException("that chunk isn't loaded");
                    return Compat.teleport(e, level, a.number(1), a.number(2), a.number(3));
                })
                .function("remove", a -> {
                    entity(a).discard();
                    return null;
                })
                .function("name", a -> entity(a).getName().getString())
                .function("set_name", a -> {
                    Entity e = entity(a);
                    e.setCustomName(a.get(1) == null ? null : Component.literal(a.string(1)));
                    e.setCustomNameVisible(a.get(1) != null);
                    return null;
                })
                .function("health", a -> entity(a) instanceof LivingEntity l ? (double) l.getHealth() : null);
    }

    private void world(ModuleRegistry modules) {
        modules.global("world")
                .function("time", a -> Compat.dayTime(api.level(a, 0)))
                .function("set_time", a -> {
                    Compat.setDayTime(api.level(a, 1), a.integer(0));
                    return null;
                })
                .function("weather", a -> Compat.weather(api.server()))
                .function("set_weather", a -> {
                    int ticks = (int) Math.round(a.optNumber(1, 300) * 20);
                    if (ticks < 1) throw new IllegalArgumentException("seconds must be > 0");
                    switch (a.string(0)) {
                        case "clear" -> Compat.setWeather(api.server(), ticks, 0, false, false);
                        case "rain" -> Compat.setWeather(api.server(), 0, ticks, true, false);
                        case "thunder" -> Compat.setWeather(api.server(), 0, ticks, true, true);
                        default -> throw new IllegalArgumentException("weather is 'clear', 'rain' or 'thunder'");
                    }
                    return null;
                })
                .function("sound", a -> {
                    Map<String, Object> o = a.get(4) == null ? Map.of() : a.table(4);
                    ServerLevel level = o.get("dim") == null ? api.server().overworld() : api.level((String) o.get("dim"));
                    float volume = number(o, "volume", 1);
                    double x = a.number(1), y = a.number(2), z = a.number(3);
                    api.server().getPlayerList().broadcast(null, x, y, z, 16.0 * Math.max(1, volume), level.dimension(),
                            sound(a.string(0), x, y, z, volume, number(o, "pitch", 1)));
                    return null;
                })
                .function("particles", a -> {
                    var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(ServerApi.id(a.string(0)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown particle '" + a.string(0) + "'"));
                    if (!(type instanceof SimpleParticleType simple))
                        throw new IllegalArgumentException("'" + a.string(0) + "' needs extra data; only simple particles for now");
                    Map<String, Object> o = a.get(4) == null ? Map.of() : a.table(4);
                    ServerLevel level = o.get("dim") == null ? api.server().overworld() : api.level((String) o.get("dim"));
                    int count = (int) number(o, "count", 1);
                    if (count < 1 || count > MAX_PARTICLES) throw new IllegalArgumentException("count must be 1.." + MAX_PARTICLES);
                    double spread = Math.max(0, Math.min(64, number(o, "spread", 0)));
                    return level.sendParticles(simple, a.number(1), a.number(2), a.number(3), count, spread, spread, spread, number(o, "speed", 0));
                });
    }

    // ---- helpers

    private static MobEffect effect(String id) {
        return BuiltInRegistries.MOB_EFFECT.getOptional(ServerApi.id(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown effect '" + id + "'"));
    }

    private static String effectId(MobEffectInstance e) {
        //? if >=1.20.5 {
        return String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()));
        //?} else
        /*return String.valueOf(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect()));*/
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ServerApi.id(id))
                .orElseThrow(() -> new IllegalArgumentException("unknown item '" + id + "'"));
    }

    /** {id, count, name} or null for an empty slot. */
    static Map<String, Object> item(ItemStack s) {
        if (s.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        String own = ItemCompat.customId(s), id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        m.put("id", own != null ? own : id);
        if (own != null) m.put("base", id);
        m.put("count", s.getCount());
        m.put("name", s.getHoverName().getString());
        ItemCompat.describe(s, m);
        return m;
    }

    private static int slot(ServerPlayer p, long slot) {
        int size = p.getInventory().getContainerSize();
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot must be 0.." + (size - 1));
        return (int) slot;
    }

    Entity find(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad entity id '" + id + "'");
        }
        for (ServerLevel level : api.server().getAllLevels()) {
            Entity e = level.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    private Entity entity(Args a) {
        Entity e = find(a.string(0));
        if (e == null || e instanceof Player) throw new IllegalArgumentException("entity isn't loaded");
        return e;
    }

    static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** What scripts hold: {@code {id, type}} with the entity methods. */
    static EntityRef handle(Entity e) {
        return new EntityRef(e.getUUID().toString(), typeId(e));
    }

    /** Any sound id, also ones only a resource's assets define (the client resolves it). */
    private static ClientboundSoundPacket sound(String id, double x, double y, double z, float volume, float pitch) {
        if (!(volume > 0) || volume > 16) throw new IllegalArgumentException("volume must be 0..16");
        if (!(pitch > 0) || pitch > 2) throw new IllegalArgumentException("pitch must be 0..2");
        Holder<SoundEvent> sound = Holder.direct(SoundEvent.createVariableRangeEvent(ServerApi.id(id)));
        return new ClientboundSoundPacket(sound, SoundSource.MASTER, x, y, z, volume, pitch, ThreadLocalRandom.current().nextLong());
    }

    private static float number(Map<String, Object> o, String key, double fallback) {
        Object v = o.get(key);
        if (v == null) return (float) fallback;
        if (!(v instanceof Number n)) throw new IllegalArgumentException(key + " must be a number");
        return n.floatValue();
    }

    private static int ticks(Map<String, Object> o, String key, double seconds) {
        double s = number(o, key, seconds);
        if (s < 0 || s > 3600) throw new IllegalArgumentException(key + " must be 0..3600 seconds");
        return (int) Math.round(s * 20);
    }
}

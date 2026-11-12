package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Args;
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
    private static final int MAX_ENTITIES = 256, MAX_RADIUS = 128, MAX_PARTICLES = 1000;

    private final ServerApi api;

    GameApi(ModuleRegistry modules, ServerApi api) {
        this.api = api;
        players(modules);
        inventory(modules);
        entities(modules);
        world(modules);
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
                    ItemStack stack = ItemStack.EMPTY;
                    if (a.get(2) != null) {
                        Item item = item(a.string(2));
                        long count = a.get(3) == null ? 1 : a.integer(3);
                        int max = new ItemStack(item).getMaxStackSize();
                        if (count < 1 || count > max) throw new IllegalArgumentException("count must be 1.." + max);
                        stack = new ItemStack(item, (int) count);
                    }
                    p.getInventory().setItem(slot, stack);
                    return null;
                })
                .function("count", a -> {
                    Inventory inv = api.player(a).getInventory();
                    Item item = item(a.string(1));
                    long n = 0;
                    for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
                    return n;
                })
                .function("take", a -> {
                    Inventory inv = api.player(a).getInventory();
                    Item item = item(a.string(1));
                    long want = a.get(2) == null ? 1 : a.integer(2);
                    if (want < 1) throw new IllegalArgumentException("count must be >= 1");
                    long taken = 0;
                    for (int i = 0; i < inv.getContainerSize() && taken < want; i++) {
                        ItemStack s = inv.getItem(i);
                        if (!s.is(item)) continue;
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
    private static Map<String, Object> item(ItemStack s) {
        if (s.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        m.put("count", s.getCount());
        m.put("name", s.getHoverName().getString());
        return m;
    }

    private static int slot(ServerPlayer p, long slot) {
        int size = p.getInventory().getContainerSize();
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot must be 0.." + (size - 1));
        return (int) slot;
    }

    private Entity find(String id) {
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

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** What scripts hold: {@code {id, type}}; the prelude gives it the entity methods. */
    private static Map<String, Object> handle(Entity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getUUID().toString());
        m.put("type", typeId(e));
        return m;
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

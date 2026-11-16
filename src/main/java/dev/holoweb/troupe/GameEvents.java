package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Game events for scripts. Handlers of the cancellable ones run right away; returning {@code false} cancels.
 * <pre>
 * players.on_break(fn(p, block))       players.on_place(fn(p, block))       players.on_use_block(fn(p, block))
 * players.on_use_item(fn(p, item))     players.on_use_entity(fn(p, entity)) players.on_attack(fn(p, target))
 * players.on_damage(fn(p, amount, cause))                                   (all of the above: return false cancels)
 * players.on_death(fn(p, cause))       players.on_respawn(fn(p))            players.on_dimension(fn(p, from, to))
 * entities.on_damage(fn(e, amount, cause)) (false cancels)                  entities.on_death(fn(e, cause))
 * </pre>
 * {@code block} is {@code {x, y, z, dim, id}}, {@code cause} {@code {type, attacker}} (a player, an entity or nil).
 * Use events fire for the main hand only. Server thread; only players' actions on the server side.
 */
public final class GameEvents {
    /** For the entries and mixins (block placing has no Fabric event). */
    private static GameEvents instance;

    private final ModuleRegistry modules;
    private final Module players, entities;

    GameEvents(ModuleRegistry modules) {
        this.modules = modules;
        players = modules.global("players").event("break").event("place").event("use_block").event("use_item")
                .event("use_entity").event("attack").event("damage").event("death").event("respawn").event("dimension");
        entities = modules.global("entities").event("damage").event("death");
        instance = this;
    }

    // Called by the loaders' entries (server side). The boolean ones: false cancels.

    static boolean breaking(Level level, Player player, BlockPos pos, BlockState state) {
        return !(player instanceof ServerPlayer p) || instance.allow(instance.players, "break", ServerApi.peer(p), block(level, pos, state.getBlock()));
    }

    static boolean usingBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return true;
        BlockPos pos = hit.getBlockPos();
        return instance.allow(instance.players, "use_block", ServerApi.peer(p), block(level, pos, level.getBlockState(pos).getBlock()));
    }

    static boolean usingItem(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return true;
        return instance.allow(instance.players, "use_item", ServerApi.peer(p), GameApi.item(p.getItemInHand(hand)));
    }

    static boolean usingEntity(Player player, InteractionHand hand, Entity entity) {
        if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND) return true;
        return instance.allow(instance.players, "use_entity", ServerApi.peer(p), who(entity));
    }

    static boolean attacking(Player player, Entity entity) {
        return !(player instanceof ServerPlayer p) || instance.allow(instance.players, "attack", ServerApi.peer(p), who(entity));
    }

    static boolean damaging(LivingEntity entity, DamageSource source, float amount) {
        GameEvents e = instance;
        return entity instanceof ServerPlayer p
                ? e.allow(e.players, "damage", ServerApi.peer(p), (double) amount, cause(source))
                : e.allow(e.entities, "damage", GameApi.handle(entity), (double) amount, cause(source));
    }

    static void died(LivingEntity entity, DamageSource source) {
        GameEvents e = instance;
        if (entity instanceof ServerPlayer p) e.emit(e.players, "death", ServerApi.peer(p), cause(source));
        else e.emit(e.entities, "death", GameApi.handle(entity), cause(source));
    }

    static void respawned(ServerPlayer now) {
        instance.emit(instance.players, "respawn", ServerApi.peer(now));
    }

    /** {@code from} and {@code to}: dimension ids. */
    static void changedLevel(ServerPlayer p, String from, String to) {
        instance.emit(instance.players, "dimension", ServerApi.peer(p), from, to);
    }

    /** {@code BlockItemMixin}: false cancels the placing. */
    public static boolean placing(Player player, Level level, BlockPos pos, Block block) {
        GameEvents e = instance;
        return e == null || !(player instanceof ServerPlayer p) || e.allow(e.players, "place", ServerApi.peer(p), block(level, pos, block));
    }

    /** Arguments are only built when a script listens (damage fires a lot). */
    private boolean allow(Module m, String event, Object... args) {
        return !modules.listens(m, event) || modules.emitCancellable(m, event, args);
    }

    private void emit(Module m, String event, Object... args) {
        if (modules.listens(m, event)) m.emit(event, args);
    }

    private static Map<String, Object> block(Level level, BlockPos pos, Block block) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("x", pos.getX());
        b.put("y", pos.getY());
        b.put("z", pos.getZ());
        b.put("dim", Compat.dimension((ServerLevel) level));
        b.put("id", BuiltInRegistries.BLOCK.getKey(block).toString());
        return b;
    }

    private static Object who(Entity e) {
        return GameApi.who(e);
    }

    private static Map<String, Object> cause(DamageSource source) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", source.getMsgId());
        if (source.getEntity() != null) c.put("attacker", who(source.getEntity()));
        return c;
    }
}

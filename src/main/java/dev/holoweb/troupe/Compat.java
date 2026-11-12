package dev.holoweb.troupe;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * The small differences between Minecraft versions, in one place (Stonecutter comments). Code that differs a lot
 * (networking, server packs) keeps its own blocks.
 */
final class Compat {
    private Compat() {}

    static String name(GameProfile profile) {
        //? if >=1.21.9 {
        return profile.name();
        //?} else
        /*return profile.getName();*/
    }

    static ServerLevel level(ServerPlayer p) {
        //? if >=1.21.6 {
        return p.level();
        //?} else
        /*return p.serverLevel();*/
    }

    static String dimension(ServerLevel level) {
        //? if >=1.21.11 {
        return level.dimension().identifier().toString();
        //?} else
        /*return level.dimension().location().toString();*/
    }

    static boolean teleport(ServerPlayer p, ServerLevel level, double x, double y, double z) {
        //? if >=1.21.5 {
        return p.teleportTo(level, x, y, z, java.util.Set.of(), p.getYRot(), p.getXRot(), true);
        //?} else {
        /*p.teleportTo(level, x, y, z, p.getYRot(), p.getXRot());
        return true;
        *///?}
    }

    /** Permission level: 2 gamemasters, 3 admins. */
    static boolean permission(CommandSourceStack src, int level) {
        //? if >=1.21.11 {
        return net.minecraft.commands.Commands.hasPermission(level >= 3 ? net.minecraft.commands.Commands.LEVEL_ADMINS : net.minecraft.commands.Commands.LEVEL_GAMEMASTERS).test(src);
        //?} else
        /*return src.hasPermission(level);*/
    }

    static Path serverDir(MinecraftServer s) {
        //? if >=1.21 {
        return s.getServerDirectory();
        //?} else
        /*return s.getServerDirectory().toPath();*/
    }

    // ---- game API (GameApi)

    static net.minecraft.world.effect.MobEffectInstance effect(net.minecraft.world.effect.MobEffect effect, int ticks, int amplifier, boolean particles) {
        //? if >=1.20.5 {
        var e = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
        //?} else
        /*var e = effect;*/
        return new net.minecraft.world.effect.MobEffectInstance(e, ticks, amplifier, false, particles, true);
    }

    static void removeEffect(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.effect.MobEffect effect) {
        //? if >=1.20.5 {
        entity.removeEffect(net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect));
        //?} else
        /*entity.removeEffect(effect);*/
    }

    /** The day time of the dimension's clock (ticks; 0 = sunrise, 6000 = noon). */
    static long dayTime(ServerLevel level) {
        //? if >=26.1 {
        return level.getDefaultClockTime();
        //?} else
        /*return level.getDayTime();*/
    }

    static void setDayTime(ServerLevel level, long ticks) {
        //? if >=26.1 {
        var clock = level.dimensionType().defaultClock()
                .orElseThrow(() -> new IllegalArgumentException("this dimension has no clock"));
        level.getServer().clockManager().setTotalTicks(clock, ticks);
        //?} else
        /*level.setDayTime(ticks);*/
    }

    /** Like /weather: {@code clear} ticks of clear weather, or {@code rain} ticks of rain (with or without thunder). */
    static void setWeather(MinecraftServer s, int clear, int rain, boolean raining, boolean thundering) {
        //? if >=26.1 {
        s.setWeatherParameters(clear, rain, raining, thundering);
        //?} else
        /*s.overworld().setWeatherParameters(clear, rain, raining, thundering);*/
    }

    /** The weather as last set (the level's rain flag only follows on the next tick): "clear", "rain" or "thunder". */
    static String weather(MinecraftServer s) {
        //? if >=26.1 {
        var w = s.overworld().getWeatherData();
        //?} else
        /*var w = s.overworld().getLevelData();*/
        return w.isRaining() ? (w.isThundering() ? "thunder" : "rain") : "clear";
    }

    static net.minecraft.world.entity.Entity spawn(net.minecraft.world.entity.EntityType<?> type, ServerLevel level, net.minecraft.core.BlockPos pos) {
        //? if >=1.21.2 {
        return type.spawn(level, pos, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        //?} else
        /*return type.spawn(level, pos, net.minecraft.world.entity.MobSpawnType.COMMAND);*/
    }

    static boolean teleport(net.minecraft.world.entity.Entity e, ServerLevel level, double x, double y, double z) {
        //? if >=1.21.5 {
        return e.teleportTo(level, x, y, z, java.util.Set.of(), e.getYRot(), e.getXRot(), false);
        //?} else
        /*return e.teleportTo(level, x, y, z, java.util.Set.of(), e.getYRot(), e.getXRot());*/
    }

    static net.minecraft.world.entity.ai.attributes.AttributeInstance attribute(net.minecraft.world.entity.LivingEntity e, net.minecraft.world.entity.ai.attributes.Attribute attribute) {
        //? if >=1.20.5 {
        return e.getAttribute(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute));
        //?} else
        /*return e.getAttribute(attribute);*/
    }

    static void syncMotion(net.minecraft.world.entity.Entity e) {
        //? if >=26.1 {
        e.syncVelocity = true;
        //?} else
        /*e.hurtMarked = true;*/
    }

    // ---- scoreboard, teams, bossbars (ScoreApi)

    /** A sidebar objective (no score numbers where the game can hide them, 1.20.3+). */
    static net.minecraft.world.scores.Objective sidebar(net.minecraft.world.scores.Scoreboard board, String name, net.minecraft.network.chat.Component title) {
        var dummy = net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY;
        var integer = net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER;
        //? if >=1.20.3 {
        var o = board.addObjective(name, dummy, title, integer, false, net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        board.setDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR, o);
        //?} else {
        /*var o = board.addObjective(name, dummy, title, integer);
        board.setDisplayObjective(1, o); // the sidebar slot
        *///?}
        return o;
    }

    static void score(net.minecraft.world.scores.Scoreboard board, net.minecraft.world.scores.Objective o, String holder, int value) {
        //? if >=1.20.3 {
        board.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(holder), o).set(value);
        //?} else
        /*board.getOrCreatePlayerScore(holder, o).setScore(value);*/
    }

    /** {@code color}: a chat color name ("red", "dark_aqua"...), or null for none. */
    static void teamColor(net.minecraft.world.scores.PlayerTeam team, String color) {
        //? if >=26.1 {
        team.setColor(color == null ? java.util.Optional.empty() : java.util.Optional.of(net.minecraft.world.scores.TeamColor.valueOf(color.toUpperCase(java.util.Locale.ROOT))));
        //?} else
        /*team.setColor(color == null ? net.minecraft.ChatFormatting.RESET : net.minecraft.ChatFormatting.valueOf(color.toUpperCase(java.util.Locale.ROOT)));*/
    }

    static net.minecraft.server.level.ServerBossEvent bossBar(net.minecraft.network.chat.Component name, net.minecraft.world.BossEvent.BossBarColor color, net.minecraft.world.BossEvent.BossBarOverlay overlay) {
        //? if >=26.1 {
        return new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(), name, color, overlay);
        //?} else
        /*return new net.minecraft.server.level.ServerBossEvent(name, color, overlay);*/
    }
}

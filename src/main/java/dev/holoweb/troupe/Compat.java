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
}

package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.api.TroupeModule;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What differs between Fabric and NeoForge besides the events (those are wired in the *Entry classes). */
final class Loader {
    private Loader() {}

    //? if fabric {
    static String version() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("troupe")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    static Path configDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }

    static Path gameDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
    }

    static boolean isLoaded(String mod) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(mod);
    }

    /** The mods' Troupe modules for this side, with the id of the mod each comes from: entrypoints {@code troupe} and {@code troupe-<side>}. */
    static List<Map.Entry<String, TroupeModule>> modules(Side side) {
        List<Map.Entry<String, TroupeModule>> out = new ArrayList<>();
        for (String entrypoint : new String[]{"troupe", side == Side.CLIENT ? "troupe-client" : "troupe-server"})
            for (var c : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers(entrypoint, TroupeModule.class))
                out.add(Map.entry(c.getProvider().getMetadata().getId(), c.getEntrypoint()));
        return out;
    }

    /** A permission node (LuckPerms and friends answer through fabric-permissions-api), else the op level. */
    static boolean permission(ServerPlayer player, String node, int level) {
        return me.lucko.fabric.api.permissions.v0.Permissions.check(player, node, level);
    }

    static boolean permission(CommandSourceStack src, String node, int level) {
        return me.lucko.fabric.api.permissions.v0.Permissions.check(src, node, level);
    }

    /** The players the game sends {@code entity} to. */
    static java.util.Collection<ServerPlayer> tracking(net.minecraft.world.entity.Entity entity) {
        return net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(entity);
    }
    //?} else {
    /*static String version() {
        return net.neoforged.fml.ModList.get().getModContainerById("troupe")
                .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    static Path configDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    static Path gameDir() {
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
    }

    static boolean isLoaded(String mod) {
        return net.neoforged.fml.ModList.get().isLoaded(mod);
    }

    // Services (META-INF/services/dev.holoweb.troupe.api.TroupeModule), called on both sides: modules.side() tells them apart.
    static List<Map.Entry<String, TroupeModule>> modules(Side side) {
        List<Map.Entry<String, TroupeModule>> out = new ArrayList<>();
        ModuleLayer layer = TroupeModule.class.getModule().getLayer();
        var services = layer != null ? java.util.ServiceLoader.load(layer, TroupeModule.class) : java.util.ServiceLoader.load(TroupeModule.class, TroupeModule.class.getClassLoader());
        for (var provider : services.stream().toList()) {
            String mod = provider.type().getModule().isNamed() ? provider.type().getModule().getName() : provider.type().getName();
            out.add(Map.entry(mod, provider.get()));
        }
        return out;
    }

    // ponytail: op level only; NeoForge's PermissionAPI wants every node declared up front, scripts make theirs up
    static boolean permission(ServerPlayer player, String node, int level) {
        return player.hasPermissions(level);
    }

    static boolean permission(CommandSourceStack src, String node, int level) {
        return src.hasPermission(level);
    }

    // What Fabric's PlayerLookup.tracking reads: the entity's ChunkMap.TrackedEntity.
    static java.util.Collection<ServerPlayer> tracking(net.minecraft.world.entity.Entity entity) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) return List.of();
        Object tracked = ((dev.holoweb.troupe.mixin.ChunkMapAccess) level.getChunkSource().chunkMap).troupe$entityMap().get(entity.getId());
        if (tracked == null) return List.of();
        return ((dev.holoweb.troupe.mixin.TrackedEntityAccess) tracked).troupe$seenBy().stream()
                .map(net.minecraft.server.network.ServerPlayerConnection::getPlayer).toList();
    }
    *///?}
}

package dev.holoweb.troupe;

import dev.holoweb.troupe.mixin.ChunkMapAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instances: players and entities put in one ({@code player:set_instance('apt_12')}) only see, and are only seen by,
 * who is in the same one; everyone else is in the world's default (no instance). Same world, same blocks: only
 * entities are hidden, by the server no longer sending them ({@code TrackedEntityMixin}). For apartments, minigames
 * and tutorials that happen in the same place for different people.
 */
public final class Instances {
    /** A handle to one entity's tracking, to run the game's "should this player see it?" again. */
    public interface Tracked {
        void troupe$update(ServerPlayer player);
    }

    private record In(String instance, String resource) {}

    private static final Map<UUID, In> instances = new ConcurrentHashMap<>();

    private Instances() {}

    /** Whether the server should show {@code entity} to {@code player}. */
    public static boolean sees(ServerPlayer player, Entity entity) {
        if (instances.isEmpty()) return true;
        return Objects.equals(of(player.getUUID()), of(entity.getUUID()));
    }

    private static String of(UUID id) {
        In in = instances.get(id);
        return in == null ? null : in.instance();
    }

    /** {@code instance} null: back to the world's default. */
    static void set(Entity entity, String instance, String resource) {
        if (instance == null) instances.remove(entity.getUUID());
        else instances.put(entity.getUUID(), new In(resource + ":" + instance, resource));
        refresh(entity);
    }

    /** The instance {@code resource} put the entity in (its name), or null. */
    static String get(Entity entity, String resource) {
        In in = instances.get(entity.getUUID());
        return in == null || !in.resource().equals(resource) ? null : in.instance().substring(resource.length() + 1);
    }

    /** The entity is gone for good, or the player left. */
    static void forget(UUID id) {
        instances.remove(id);
    }

    /** The resource stopped: whoever it had put in an instance is back in the world. */
    static void forget(String resource, MinecraftServer server) {
        for (var e : instances.entrySet()) {
            if (!e.getValue().resource().equals(resource)) continue;
            instances.remove(e.getKey());
            if (server != null) for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(e.getKey());
                if (entity != null) refresh(entity);
            }
        }
    }

    /** Who sees {@code changed}, and (when it is a player) what it sees, decided again now instead of when it next moves. */
    private static void refresh(Entity changed) {
        if (!(changed.level() instanceof ServerLevel level)) return;
        var tracked = ((ChunkMapAccess) level.getChunkSource().chunkMap).troupe$entityMap();
        if (changed instanceof ServerPlayer p) for (Object t : tracked.values()) ((Tracked) t).troupe$update(p);
        Object own = tracked.get(changed.getId());
        if (own != null) for (ServerPlayer p : level.players()) ((Tracked) own).troupe$update(p);
    }
}

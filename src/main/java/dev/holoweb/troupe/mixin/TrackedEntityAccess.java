package dev.holoweb.troupe.mixin;
//? if neoforge {
/*
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

// NeoForge: who an entity is sent to (Loader.tracking; Fabric has PlayerLookup.tracking). Line comments only: this file is a block comment on Fabric.
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccess {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> troupe$seenBy();
}
*///?}

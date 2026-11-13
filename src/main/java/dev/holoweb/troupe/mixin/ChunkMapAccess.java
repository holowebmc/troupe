package dev.holoweb.troupe.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every tracked entity of a level by entity id (the values are {@code ChunkMap.TrackedEntity}, see TrackedEntityMixin). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Accessor("entityMap")
    Int2ObjectMap<?> troupe$entityMap();
}

package dev.holoweb.troupe.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's list of key mappings, to add and remove the ones resources register while they run. */
@Mixin(Options.class)
public interface OptionsAccess {
    @Mutable
    @Accessor("keyMappings")
    void troupe$setKeyMappings(KeyMapping[] keys);
}

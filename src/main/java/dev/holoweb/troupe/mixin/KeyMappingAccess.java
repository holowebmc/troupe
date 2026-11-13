package dev.holoweb.troupe.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Every key mapping by name: a removed one must leave it, or it keeps taking presses of its key. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
    @Accessor("ALL")
    static Map<String, KeyMapping> troupe$all() {
        throw new AssertionError();
    }
}

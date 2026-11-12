package dev.holoweb.troupe.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Consumer;

/** What {@link dev.holoweb.troupe.QuietOverlay} takes over from the game's reload overlay. */
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccess {
    @Accessor("reload")
    ReloadInstance troupe$reload();

    @Accessor("onFinish")
    Consumer<Optional<Throwable>> troupe$onFinish();
}

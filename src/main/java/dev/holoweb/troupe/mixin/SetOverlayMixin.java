package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.QuietOverlay;
import net.minecraft.client.gui.screens.Overlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Troupe's own resource reloads get {@link QuietOverlay} instead of the Mojang screen. */
//? if >=26.1 {
@Mixin(net.minecraft.client.gui.Gui.class)
//?} else
/*@Mixin(net.minecraft.client.Minecraft.class)*/
public abstract class SetOverlayMixin {
    @ModifyVariable(method = "setOverlay", at = @At("HEAD"), argsOnly = true)
    private Overlay troupe$quiet(Overlay overlay) {
        return QuietOverlay.wrap(overlay);
    }
}

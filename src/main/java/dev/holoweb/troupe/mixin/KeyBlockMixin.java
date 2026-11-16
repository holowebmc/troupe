package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.KeyBlocks;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** keys.block: a blocked mapping is never down and its clicks are dropped (not kept for later). */
@Mixin(KeyMapping.class)
public abstract class KeyBlockMixin {
    @Shadow private int clickCount;

    @Shadow public abstract String getName();

    @Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
    private void troupe$blockClick(CallbackInfoReturnable<Boolean> cir) {
        if (KeyBlocks.blocked(getName())) {
            clickCount = 0;
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void troupe$blockDown(CallbackInfoReturnable<Boolean> cir) {
        if (KeyBlocks.blocked(getName())) cir.setReturnValue(false);
    }
}

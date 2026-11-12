package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.GameEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** players.on_place: Fabric has no block placing event. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void troupe$place(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (ctx.getLevel().isClientSide()) return;
        if (!GameEvents.placing(ctx.getPlayer(), ctx.getLevel(), ctx.getClickedPos(), ((BlockItem) (Object) this).getBlock()))
            cir.setReturnValue(InteractionResult.FAIL);
    }
}

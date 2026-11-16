package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.ModelDraw;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity drawn as a resource's model (entity:set_model) skips the game's renderer: shadow and name tag too. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    //? if >=26.3 {
    private void troupe$model(Entity entity, Frustum frustum, double x, double y, double z, float partial, CallbackInfoReturnable<Boolean> cir) {
    //?} else
    /*private void troupe$model(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {*/
        if (ModelDraw.hides(entity)) cir.setReturnValue(false);
    }
}

package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.RenderFx;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** render.post, the hidden hand under render.camera, and render.fov before 26.x (where the FOV is still computed here). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** No first-person hand while render.camera holds the view elsewhere. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void troupe$hand(CallbackInfo ci) {
        if (RenderFx.camera() != null) ci.cancel();
    }

    //? if >=26.1 {
    @Shadow
    public abstract java.util.List<net.minecraft.resources.Identifier> getRequestedPostEffects();

    /** The list is rebuilt every frame; ours goes last. */
    @Inject(method = "update", at = @At("TAIL"))
    private void troupe$post(CallbackInfo ci) {
        var post = RenderFx.post;
        if (post != null) getRequestedPostEffects().add(post);
    }
    //?} else {
    /*@Shadow
    abstract void loadEffect(net.minecraft.resources.Identifier id);

    @Shadow
    public abstract void shutdownEffect();

    private net.minecraft.resources.Identifier troupe$applied;

    /^* Before 1.21.2 one post chain at a time ({@code shaders/post/<name>.json}), switched when ours changes. *^/
    @Inject(method = "render", at = @At("HEAD"))
    private void troupe$post(CallbackInfo ci) {
        var want = RenderFx.post;
        if (java.util.Objects.equals(want, troupe$applied)) return;
        troupe$applied = want;
        if (want == null) shutdownEffect();
        else loadEffect(new net.minecraft.resources.Identifier(want.getNamespace(), "shaders/post/" + want.getPath() + ".json"));
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void troupe$fov(CallbackInfoReturnable<Double> cir) {
        if (RenderFx.fov != 1) cir.setReturnValue(cir.getReturnValue() * RenderFx.fov);
    }
    *///?}
}

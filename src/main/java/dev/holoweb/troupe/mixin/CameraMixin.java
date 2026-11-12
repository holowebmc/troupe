package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.RenderFx;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** render.camera (fixed camera) and render.shake; on 26.x also render.fov (the FOV lives in the camera there). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private float xRot;
    @Shadow
    private float yRot;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    //? if >=26.1 {
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    //?} else
    /*@Inject(method = "setup", at = @At("TAIL"))*/
    private void troupe$override(CallbackInfo ci) {
        double[] cam = RenderFx.camera;
        if (cam != null) {
            setPosition(cam[0], cam[1], cam[2]);
            setRotation((float) cam[3], (float) cam[4]);
        }
        float[] shake = RenderFx.shake();
        if (shake != null) setRotation(yRot + shake[0], xRot + shake[1]);
    }

    /** With a fixed camera the player's own body shows, as in third person. */
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void troupe$detached(CallbackInfoReturnable<Boolean> cir) {
        if (RenderFx.camera != null) cir.setReturnValue(true);
    }

    //? if >=26.1 {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void troupe$fov(float partial, CallbackInfoReturnable<Float> cir) {
        if (RenderFx.fov != 1) cir.setReturnValue(cir.getReturnValue() * RenderFx.fov);
    }
    //?}
}

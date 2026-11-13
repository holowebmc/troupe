package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.Instances;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instances: an entity in another instance is never sent to the player (and is taken away if it was). */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin implements Instances.Tracked {
    @Shadow
    @Final
    Entity entity;

    @Shadow
    public abstract void removePlayer(ServerPlayer player);

    @Shadow
    public abstract void updatePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void troupe$instances(ServerPlayer player, CallbackInfo ci) {
        if (player != entity && !Instances.sees(player, entity)) {
            removePlayer(player);
            ci.cancel();
        }
    }

    @Override
    public void troupe$update(ServerPlayer player) {
        updatePlayer(player);
    }
}

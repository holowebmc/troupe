package dev.holoweb.troupe.mixin;

import dev.holoweb.troupe.ServerBadge;
import dev.holoweb.troupe.TroupeStatus;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The pinger's status listener: hands the ping's troupe info to the server entry it's for. */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
public abstract class ServerStatusPingerMixin {
    @Shadow
    @Final
    ServerData val$data;

    @Inject(method = "handleStatusResponse", at = @At("HEAD"))
    private void troupe$badge(ClientboundStatusResponsePacket packet, CallbackInfo ci) {
        ServerBadge.set(val$data, TroupeStatus.received(packet.status()));
    }
}

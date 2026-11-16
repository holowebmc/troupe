package dev.holoweb.troupe.mixin;
//? if neoforge {
/*
import dev.holoweb.troupe.TroupeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// NeoForge: what Fabric's ClientConfigurationConnectionEvents.INIT / DISCONNECT and ClientPlayConnectionEvents.DISCONNECT
// tell (NeoForgeClientEntry). Same places as Fabric's: the listener's constructor and onDisconnect.
// Line comments only: this file is a block comment on Fabric.
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientConnectionMixin {
    @Shadow
    @Final
    protected ServerData serverData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void troupe$opened(Minecraft minecraft, Connection connection, CommonListenerCookie cookie, CallbackInfo ci) {
        if ((Object) this instanceof ClientConfigurationPacketListenerImpl) TroupeClient.configStarted(serverData);
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void troupe$closed(DisconnectionDetails details, CallbackInfo ci) {
        if ((Object) this instanceof ClientConfigurationPacketListenerImpl) TroupeClient.configEnded();
        else TroupeClient.playEnded();
    }
}
*///?}

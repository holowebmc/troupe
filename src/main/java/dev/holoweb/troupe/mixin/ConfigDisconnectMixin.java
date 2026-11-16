package dev.holoweb.troupe.mixin;
//? if neoforge {
/*
import dev.holoweb.troupe.ConfigPhase;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// NeoForge: a player who leaves while configuring (Fabric's ServerConfigurationConnectionEvents.DISCONNECT) frees their download.
// Line comments only: this file is a block comment on Fabric.
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ConfigDisconnectMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void troupe$closed(DisconnectionDetails details, CallbackInfo ci) {
        ConfigPhase.disconnected((ServerConfigurationPacketListenerImpl) (Object) this);
    }
}
*///?}

package dev.holoweb.troupe.mixin;

//? if >=1.20.3 {
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server being joined, while still configuring ({@code Minecraft.getCurrentServer()} only sees the play connection). */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface CommonListenerAccess {
    @Accessor("serverData")
    ServerData troupe$serverData();
}
//?}

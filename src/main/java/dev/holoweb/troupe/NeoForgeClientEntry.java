package dev.holoweb.troupe;
//? if neoforge {
/*
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

// NeoForge entry (client): the client half of Troupe on NeoForge's events. A connection opening for configuration
// and either phase closing come from ClientConnectionMixin (no events for those). Line comments only: this file is a block comment on Fabric.
@Mod(value = "troupe", dist = Dist.CLIENT)
public final class NeoForgeClientEntry {
    public NeoForgeClientEntry(IEventBus modBus) {
        TroupeClient.init();
        // Netty thread (NeoForgeEntry's registration): configuration stays there, as with Fabric; play hops to the render thread.
        NeoForgeEntry.client = (payload, ctx) -> {
            if (ctx.protocol() == ConnectionProtocol.CONFIGURATION)
                TroupeClient.configReceived(payload.data(), m -> ctx.reply(new TroupePayload(m)), ctx::disconnect);
            else ctx.enqueueWork(() -> TroupeClient.receive(payload.data(), (LocalPlayer) ctx.player()));
        };
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(ClientKeys.interact));
        modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(Identifier.fromNamespaceAndPath("troupe", "hud"),
                (gui, delta) -> TroupeClient.hud().draw(gui)));

        IEventBus bus = NeoForge.EVENT_BUS;
        // hud.native(false): first in line, so a mod that draws in its own listener for a vanilla layer (TaCZ's crosshair) doesn't run either.
        ClientHud.layersHideable = true;
        bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre e) -> {
            if (TroupeClient.hud().hides(e.getName())) e.setCanceled(true);
        });
        // The login packet: the first one of play, handled on the render thread before ours.
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) ->
                TroupeClient.playStarted(m -> PacketDistributor.sendToServer(new TroupePayload(m)), e.getConnection()));
        bus.addListener((RenderLevelStageEvent e) -> {
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
                TroupeClient.world().render(e.getCamera(), e.getPoseStack(), Minecraft.getInstance().renderBuffers().bufferSource());
        });
        bus.addListener((ClientTickEvent.Post e) -> TroupeClient.tick(Minecraft.getInstance()));
    }
}
*///?}

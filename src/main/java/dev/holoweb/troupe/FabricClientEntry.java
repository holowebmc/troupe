package dev.holoweb.troupe;
//? if fabric {

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=1.20.5 {
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

// Fabric entry (client): the client half of Troupe on Fabric API's events. Line comments only: this file is a block comment on NeoForge.
public final class FabricClientEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TroupeClient.init();

        //? if >=1.20.5 {
        ClientConfigurationConnectionEvents.INIT.register((handler, client) ->
                TroupeClient.configStarted(((dev.holoweb.troupe.mixin.CommonListenerAccess) (Object) handler).troupe$serverData()));
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> TroupeClient.configEnded());
        ClientConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            var sender = ctx.responseSender();
            TroupeClient.configReceived(payload.data(), m -> sender.sendPacket(new TroupePayload(m)), sender::disconnect);
        });
        //?}

        // INIT comes before any play packet, and its task is queued ahead of their handling on the render thread.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() ->
                TroupeClient.playStarted(m -> ClientPlayNetworking.send(new TroupePayload(m)), handler.getConnection())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TroupeClient.playEnded());
        //? if >=1.20.5 {
        ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> TroupeClient.receive(payload.data(), ctx.player()));
        //?} else
        /*ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, player, sender) -> TroupeClient.receive(payload.data(), player));*/

        //? if >=26.1 {
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                TroupeClient.world().render(ctx.levelState().cameraRenderState, ctx.poseStack(), ctx.submitNodeCollector()));
        //?} else if >=1.21.9 {
        /*net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.BEFORE_ENTITIES.register(ctx ->
                TroupeClient.world().render(ctx.worldState().cameraRenderState, ctx.matrices(), ctx.commandQueue()));
        *///?} else
        /*net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(ctx -> TroupeClient.world().render(ctx.camera(), ctx.matrixStack(), ctx.consumers()));*/
        //? if >=1.21.6 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("troupe", "hud"), (gui, delta) -> TroupeClient.hud().draw(gui));
        //?} else
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((gui, delta) -> TroupeClient.hud().draw(gui));*/
        //? if >=26.1 {
        net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(ClientKeys.interact);
        //?} else
        /*net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(ClientKeys.interact);*/
        ClientTickEvents.END_CLIENT_TICK.register(TroupeClient::tick);
    }
}
//?}

package dev.holoweb.troupe;
//? if fabric {

import dev.holoweb.troupe.net.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
//? if >=1.20.5 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
//?}
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.util.function.Consumer;

// Fabric entry (both sides): the server half of Troupe on Fabric API's events. Line comments only: this file is a block comment on NeoForge.
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        //? if >=1.20.5 {
        PayloadTypeRegistry.clientboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, s) -> {
            var task = ConfigPhase.configure(handler, ServerConfigurationNetworking.canSend(handler, TroupePayload.TYPE),
                    sender(ServerConfigurationNetworking.getSender(handler), handler::disconnect));
            if (task != null) handler.addTask(task);
        });
        // Fabric runs configuration receivers on the Netty thread (unlike play): the task queue belongs to the server thread.
        ServerConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            var handler = ctx.packetListener();
            ConfigPhase.receive(handler, payload.data(), () -> ctx.server().execute(() -> handler.completeTask(ConfigPhase.TASK)));
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, s) -> ConfigPhase.disconnected(handler));
        //?}
        Troupe.init();

        EntityTrackingEvents.START_TRACKING.register(Troupe::startTracking);
        EntityTrackingEvents.STOP_TRACKING.register(Troupe::stopTracking);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> Troupe.entityUnloaded(entity));
        ServerLifecycleEvents.SERVER_STARTING.register(Troupe::starting);
        ServerLifecycleEvents.SERVER_STARTED.register(Troupe::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Troupe.stopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> Troupe.stopped());
        ServerTickEvents.END_SERVER_TICK.register(Troupe::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Troupe.command()));
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> Troupe.chat(sender, message.signedContent()));

        // Play receivers run on the server thread.
        ServerPlayConnectionEvents.JOIN.register((handler, packets, s) -> Troupe.joined(handler.player,
                ServerPlayNetworking.canSend(handler.player, TroupePayload.TYPE),
                sender(ServerPlayNetworking.getSender(handler.player), reason -> handler.player.connection.disconnect(reason))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> Troupe.left(handler.player, s));
        //? if >=1.20.5 {
        ServerPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> Troupe.receive(payload.data(), ctx.player()));
        //?} else
        /*ServerPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, player, packets) -> Troupe.receive(payload.data(), player));*/

        gameEvents();
    }

    // Script events (GameEvents): false from it cancels.
    private static void gameEvents() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> GameEvents.breaking(level, player, pos, state));
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                GameEvents.usingBlock(player, level, hand, hit) ? InteractionResult.PASS : InteractionResult.FAIL);
        //? if >=1.21.2 {
        UseItemCallback.EVENT.register((player, level, hand) -> GameEvents.usingItem(player, hand) ? InteractionResult.PASS : InteractionResult.FAIL);
        //?} else {
        /*UseItemCallback.EVENT.register((player, level, hand) -> {
            var stack = player.getItemInHand(hand);
            return GameEvents.usingItem(player, hand)
                    ? net.minecraft.world.InteractionResultHolder.pass(stack) : net.minecraft.world.InteractionResultHolder.fail(stack);
        });
        *///?}
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                GameEvents.usingEntity(player, hand, entity) ? InteractionResult.PASS : InteractionResult.FAIL);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                GameEvents.attacking(player, entity) ? InteractionResult.PASS : InteractionResult.FAIL);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(GameEvents::damaging);
        ServerLivingEntityEvents.AFTER_DEATH.register(GameEvents::died);
        ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> GameEvents.respawned(now));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) ->
                GameEvents.changedLevel(p, Compat.dimension(from), Compat.dimension(to)));
    }

    // disconnect: PacketSender only has it from 1.20.5.
    private static ServerSync.Sender sender(PacketSender packets, Consumer<Component> disconnect) {
        return new ServerSync.Sender() {
            @Override
            public void send(byte[] message, Runnable onSent) {
                TroupePayload payload = new TroupePayload(message);
                if (onSent == null) packets.sendPacket(payload);
                //? if >=1.21.6 {
                else packets.sendPacket(payload, future -> {
                    if (future.isSuccess()) onSent.run();
                });
                //?} else
                /*else packets.sendPacket(payload, net.minecraft.network.PacketSendListener.thenRun(onSent));*/
            }

            @Override
            public void disconnect(String reason) {
                disconnect.accept(Component.literal(reason));
            }
        };
    }
}
//?}

package dev.holoweb.troupe;
//? if neoforge {
/*
import dev.holoweb.troupe.net.ServerSync;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.util.function.BiConsumer;

// NeoForge entry (both sides): the server half of Troupe on NeoForge's events (only 1.21.1 for now). Configuration
// disconnects come from ConfigDisconnectMixin. Line comments only: this file is a block comment on Fabric.
@Mod("troupe")
public final class NeoForgeEntry {
    // What the client does with a Troupe message; set by NeoForgeClientEntry, so this class (which servers load) never names client classes.
    static volatile BiConsumer<TroupePayload, IPayloadContext> client = (payload, ctx) -> {};

    public NeoForgeEntry(IEventBus modBus) {
        Troupe.init();
        // Optional: players without Troupe still join servers with no resources. Handlers on the Netty thread, as
        // Fabric's configuration ones; play hops to the game thread, as Fabric's play ones run.
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").optional().executesOn(HandlerThread.NETWORK)
                .commonBidirectional(TroupePayload.TYPE, TroupePayload.CODEC, NeoForgeEntry::receive));
        modBus.addListener((RegisterConfigurationTasksEvent e) -> {
            var handler = (ServerConfigurationPacketListenerImpl) e.getListener();
            var task = ConfigPhase.configure(handler, handler.hasChannel(TroupePayload.TYPE), sender(handler));
            if (task != null) e.register(task);
        });

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((PlayerEvent.StartTracking e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Troupe.startTracking(e.getTarget(), p);
        });
        bus.addListener((PlayerEvent.StopTracking e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Troupe.stopTracking(e.getTarget(), p);
        });
        bus.addListener((EntityLeaveLevelEvent e) -> {
            if (!e.getLevel().isClientSide()) Troupe.entityUnloaded(e.getEntity());
        });
        bus.addListener((ServerAboutToStartEvent e) -> Troupe.starting(e.getServer())); // before the worlds load, as Fabric's SERVER_STARTING
        bus.addListener((ServerStartedEvent e) -> Troupe.started(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> Troupe.stopping());
        bus.addListener((ServerStoppedEvent e) -> Troupe.stopped());
        bus.addListener((ServerTickEvent.Post e) -> Troupe.tick(e.getServer()));
        bus.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(Troupe.command()));
        // Last, and only if nobody cancelled it: the message went out, as with Fabric's CHAT_MESSAGE.
        bus.addListener(EventPriority.LOWEST, (ServerChatEvent e) -> Troupe.chat(e.getPlayer(), e.getRawText()));
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Troupe.joined(p, p.connection.hasChannel(TroupePayload.TYPE), sender(p.connection));
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) Troupe.left(p, p.server);
        });
        gameEvents(bus);
    }

    private static void receive(TroupePayload payload, IPayloadContext ctx) {
        if (ctx.flow() == PacketFlow.CLIENTBOUND) client.accept(payload, ctx);
        else if (ctx.protocol() == ConnectionProtocol.CONFIGURATION)
            ConfigPhase.receive((ServerConfigurationPacketListenerImpl) ctx.listener(), payload.data(),
                    () -> ctx.enqueueWork(() -> ctx.finishCurrentTask(ConfigPhase.TASK)));
        else ctx.enqueueWork(() -> Troupe.receive(payload.data(), (ServerPlayer) ctx.player()));
    }

    // Script events (GameEvents): false from it cancels.
    private static void gameEvents(IEventBus bus) {
        bus.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getLevel() instanceof Level level && !GameEvents.breaking(level, e.getPlayer(), e.getPos(), e.getState())) e.setCanceled(true);
        });
        bus.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (GameEvents.usingBlock(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec())) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        });
        bus.addListener((PlayerInteractEvent.RightClickItem e) -> {
            if (GameEvents.usingItem(e.getEntity(), e.getHand())) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        });
        bus.addListener((PlayerInteractEvent.EntityInteract e) -> {
            if (GameEvents.usingEntity(e.getEntity(), e.getHand(), e.getTarget())) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        });
        bus.addListener((AttackEntityEvent e) -> {
            if (!GameEvents.attacking(e.getEntity(), e.getTarget())) e.setCanceled(true);
        });
        bus.addListener((LivingIncomingDamageEvent e) -> {
            if (!e.getEntity().level().isClientSide() && !GameEvents.damaging(e.getEntity(), e.getSource(), e.getAmount())) e.setCanceled(true);
        });
        // Last, and only if nobody saved the entity: it died, as with Fabric's AFTER_DEATH.
        bus.addListener(EventPriority.LOWEST, (LivingDeathEvent e) -> {
            if (!e.getEntity().level().isClientSide()) GameEvents.died(e.getEntity(), e.getSource());
        });
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) GameEvents.respawned(p);
        });
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) GameEvents.changedLevel(p, Compat.dimension(e.getFrom()), Compat.dimension(e.getTo()));
        });
    }

    // Play and configuration: the vanilla connection, with a callback once the packet is out.
    private static ServerSync.Sender sender(ServerCommonPacketListenerImpl handler) {
        return new ServerSync.Sender() {
            @Override
            public void send(byte[] message, Runnable onSent) {
                handler.send(new ClientboundCustomPayloadPacket(new TroupePayload(message)), onSent == null ? null : PacketSendListener.thenRun(onSent));
            }

            @Override
            public void disconnect(String reason) {
                handler.disconnect(Component.literal(reason));
            }
        };
    }
}
*///?}

package dev.holoweb.troupe;

//? if >=1.20.5 {
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Download during the configuration phase (1.20.2+; here 1.20.5+, with typed payloads): the player waits on the
 * loading screen until every client file is cached. Before that the download happens in play (see {@code Link.push}).
 */
final class ConfigPhase {
    private static final Map<ServerConfigurationPacketListenerImpl, ServerSync> syncs = new ConcurrentHashMap<>();

    private ConfigPhase() {}

    /** {@code configured}: what each player got, so play can push the newer manifest if it changed meanwhile. */
    static void register(Supplier<ServerResources> published, Map<UUID, ServerResources> configured) {
        PayloadTypeRegistry.clientboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, s) -> {
            ServerResources current = published.get();
            configured.put(handler.getOwner().id(), current);
            if (current.manifest().resources().isEmpty()) return;
            if (!ServerConfigurationNetworking.canSend(handler, TroupePayload.TYPE)) {
                handler.disconnect(Component.translatableWithFallback("troupe.disconnect.missing_mod", "This server needs the Troupe mod."));
                return;
            }
            ServerSync sync = new ServerSync(current, Troupe.sender(ServerConfigurationNetworking.getSender(handler), handler::disconnect));
            syncs.put(handler, sync);
            handler.addTask(new SyncTask(sync));
        });

        // Fabric runs configuration receivers on the Netty thread (unlike play): the task queue belongs to the server thread.
        ServerConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            ServerConfigurationPacketListenerImpl handler = ctx.packetListener();
            ServerSync sync = syncs.get(handler);
            if (sync == null) return;
            try {
                if (sync.receive(payload.data())) {
                    syncs.remove(handler).close();
                    ctx.server().execute(() -> handler.completeTask(SyncTask.TYPE));
                }
            } catch (IOException e) {
                Troupe.LOG.warn("Troupe protocol error from {}: {}", Compat.name(handler.getOwner()), e.getMessage());
                handler.disconnect(Component.literal("Troupe: " + e.getMessage()));
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerSync sync = syncs.remove(handler);
            if (sync != null) sync.close();
        });
    }

    /** Holds the player in configuration until the client says every file is cached. */
    private record SyncTask(ServerSync sync) implements ConfigurationTask {
        static final Type TYPE = new Type("troupe:sync");

        @Override
        public void start(Consumer<Packet<?>> send) {
            sync.start();
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }
}
//?} else {
/*import dev.holoweb.troupe.net.ServerResources;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/^* No configuration phase before 1.20.2: clients download in play, when the server pushes the manifest. *^/
final class ConfigPhase {
    private ConfigPhase() {}

    static void register(Supplier<ServerResources> published, Map<UUID, ServerResources> configured) {}
}
*///?}

package dev.holoweb.troupe;

import dev.holoweb.troupe.net.ProtocolException;
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Server side (dedicated or integrated): scans {@code resources/} and sends the client files during configuration. */
public final class Troupe implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("troupe");

    private static volatile ServerResources resources = ServerResources.EMPTY;
    private static final Map<ServerConfigurationPacketListenerImpl, ServerSync> syncs = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                resources = ServerResources.scan(server.getServerDirectory().resolve("resources"), w -> LOG.warn("Resource {}", w));
            } catch (IOException e) {
                LOG.error("Could not read the resources folder", e);
                resources = ServerResources.EMPTY;
            }
            long bytes = resources.files().stream().mapToLong(f -> f.toFile().length()).sum();
            LOG.info("{} resources with client files ({} files, {} KiB): {}", resources.manifest().resources().size(),
                    resources.files().size(), bytes >> 10, resources.manifest().resources().keySet());
        });

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            ServerResources current = resources;
            if (current.manifest().resources().isEmpty()) return;
            if (!ServerConfigurationNetworking.canSend(handler, TroupePayload.TYPE)) {
                handler.disconnect(Component.translatableWithFallback("troupe.disconnect.missing_mod", "This server needs the Troupe mod."));
                return;
            }
            ServerSync sync = new ServerSync(current, sender(ServerConfigurationNetworking.getSender(handler)));
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
            } catch (ProtocolException e) {
                LOG.warn("Troupe protocol error from {}: {}", handler.getOwner().name(), e.getMessage());
                handler.disconnect(Component.literal("Troupe: " + e.getMessage()));
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerSync sync = syncs.remove(handler);
            if (sync != null) sync.close();
        });
    }

    private static ServerSync.Sender sender(PacketSender packets) {
        return new ServerSync.Sender() {
            @Override
            public void send(byte[] message, Runnable onSent) {
                TroupePayload payload = new TroupePayload(message);
                if (onSent == null) packets.sendPacket(payload);
                else packets.sendPacket(payload, future -> {
                    if (future.isSuccess()) onSent.run();
                });
            }

            @Override
            public void disconnect(String reason) {
                packets.disconnect(Component.literal(reason));
            }
        };
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

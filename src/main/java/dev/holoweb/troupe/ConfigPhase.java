package dev.holoweb.troupe;

//? if >=1.20.5 {
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;
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
 * The loaders' entries call {@link #configure}, {@link #receive} and {@link #disconnected}.
 */
public final class ConfigPhase {
    private static final Map<ServerConfigurationPacketListenerImpl, ServerSync> syncs = new ConcurrentHashMap<>();
    private static Supplier<ServerResources> published;
    private static Map<UUID, ServerResources> configured;
    static final ConfigurationTask.Type TASK = SyncTask.TYPE;

    private ConfigPhase() {}

    /** {@code configured}: what each player got, so play can push the newer manifest if it changed meanwhile. */
    static void init(Supplier<ServerResources> published, Map<UUID, ServerResources> configured) {
        ConfigPhase.published = published;
        ConfigPhase.configured = configured;
    }

    /** Configuration starts ({@code hasTroupe}: the client listens on our channel): the task to add, or null. */
    static ConfigurationTask configure(ServerConfigurationPacketListenerImpl handler, boolean hasTroupe, ServerSync.Sender sender) {
        ServerResources current = published.get();
        configured.put(Compat.id(handler.getOwner()), current);
        if (current.manifest().resources().isEmpty()) return null;
        if (!hasTroupe) {
            handler.disconnect(Component.translatableWithFallback("troupe.disconnect.missing_mod", "This server needs the Troupe mod."));
            return null;
        }
        ServerSync sync = new ServerSync(current, sender);
        syncs.put(handler, sync);
        return new SyncTask(sync);
    }

    /**
     * A Troupe message during configuration. Netty thread (Fabric runs configuration receivers there, unlike play);
     * {@code done} completes {@link #TASK}, and must hop to the server thread, which owns the task queue.
     */
    static void receive(ServerConfigurationPacketListenerImpl handler, byte[] data, Runnable done) {
        ServerSync sync = syncs.get(handler);
        if (sync == null) return;
        try {
            if (sync.receive(data)) {
                syncs.remove(handler).close();
                done.run();
            }
        } catch (IOException e) {
            Troupe.LOG.warn("Troupe protocol error from {}: {}", Compat.name(handler.getOwner()), e.getMessage());
            handler.disconnect(Component.literal("Troupe: " + e.getMessage()));
        }
    }

    public static void disconnected(ServerConfigurationPacketListenerImpl handler) {
        ServerSync sync = syncs.remove(handler);
        if (sync != null) sync.close();
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

    static void init(Supplier<ServerResources> published, Map<UUID, ServerResources> configured) {}
}
*///?}

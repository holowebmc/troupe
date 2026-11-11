package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.Link;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.net.ClientSync;
import dev.holoweb.troupe.net.Protocol;
import dev.holoweb.troupe.net.Protocol.Manifest;
import dev.holoweb.troupe.net.ResourceInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Client side: downloads the server's resources into the shared cache while the connection is configuring, runs
 * them while in the world.
 */
public final class TroupeClient implements ClientModInitializer {
    private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("troupe/cache");
    private static final ResourceHost host = new ResourceHost(Side.CLIENT, task -> Minecraft.getInstance().execute(task));
    private static final ClientApi api = new ClientApi(host.modules());

    // Written on the Netty thread (Fabric runs configuration receivers there), read from the render thread.
    private static volatile ClientSync sync;
    /** Resources of the server we are on (empty when it has none). */
    private static volatile Manifest manifest = Manifest.EMPTY;
    /** The play connection to the server; render thread only. */
    private static Link link;

    public static Manifest manifest() {
        return manifest;
    }

    @Override
    public void onInitializeClient() {
        Troupe.registerModules(host.modules());
        if (FabricLoader.getInstance().isModLoaded("vitrine")) host.setUi(VitrineUi.create());
        else Troupe.LOG.info("Vitrine isn't installed: resource pages (ui.*) won't open");

        ClientConfigurationConnectionEvents.INIT.register((handler, client) -> {
            reset();
            manifest = Manifest.EMPTY;
        });
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> reset());

        ClientConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            if (sync == null) sync = new ClientSync(CACHE, m -> ctx.responseSender().sendPacket(new TroupePayload(m)));
            try {
                Manifest done = sync.receive(payload.data());
                if (done != null) {
                    Troupe.LOG.info("{} resources ready ({} files downloaded, {} KiB): {}", done.resources().size(),
                            sync.downloadedFiles(), sync.downloadedBytes() >> 10, done.resources().keySet());
                    manifest = done;
                    sync = null;
                }
            } catch (IOException e) {
                Troupe.LOG.warn("Resource download failed", e);
                reset();
                ctx.responseSender().disconnect(Component.translatableWithFallback("troupe.disconnect.sync_failed", "Troupe: %s", e.getMessage()));
            }
        });

        // INIT comes before any play packet, and its task is queued ahead of their handling on the render thread.
        // The resources start when the server says START (after it sent the global state).
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> {
            link = new Link(host, new Link.Out() {
                public void send(byte[] message, Runnable onSent) {
                    ClientPlayNetworking.send(new TroupePayload(message));
                    if (onSent != null) onSent.run();
                }

                public void disconnect(String reason) {
                    handler.getConnection().disconnect(Component.literal(reason));
                }
            }, TroupeClient::startResources, CACHE, TroupeClient::applyManifest);
            host.setNetwork(link);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (link != null) link.close();
            link = null;
            host.stopAll();
            host.setNetwork(null);
            host.resetState();
        }));
        ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            if (link == null) return;
            try {
                link.receive(payload.data());
            } catch (IOException e) {
                Troupe.LOG.warn("Troupe protocol error from the server: {}", e.getMessage());
                ctx.player().connection.getConnection().disconnect(Component.literal("Troupe: " + e.getMessage()));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            api.tick();
            host.tick();
        });
    }

    /** Starts the server's resources from the cache: only their client/ and shared/ files are reachable. */
    private static void startResources() {
        Manifest m = manifest;
        host.apply(ResourceHost.entries(m, CACHE), m.generations());
    }

    /** The server's running resources changed while we're in the world; the files are in the cache already. */
    private static void applyManifest(Manifest m) {
        Troupe.LOG.info("Server resources changed: {}", m.generations());
        manifest = m;
        host.apply(ResourceHost.entries(m, CACHE), m.generations());
    }

    private static void reset() {
        ClientSync s = sync;
        if (s != null) s.close();
        sync = null;
    }
}

package dev.holoweb.troupe;

import dev.holoweb.troupe.net.ClientSync;
import dev.holoweb.troupe.net.Protocol.Manifest;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

/** Client side: downloads the server's resources into the shared cache while the connection is configuring. */
public final class TroupeClient implements ClientModInitializer {
    private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("troupe/cache");

    // Written on the Netty thread (Fabric runs configuration receivers there), read from the render thread.
    private static volatile ClientSync sync;
    /** Resources of the server we are on (empty when it has none). */
    private static volatile Manifest manifest = Manifest.EMPTY;

    public static Manifest manifest() {
        return manifest;
    }

    @Override
    public void onInitializeClient() {
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
    }

    private static void reset() {
        if (sync != null) sync.close();
        sync = null;
    }
}

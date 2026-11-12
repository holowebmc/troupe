package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.Link;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.net.BlobCache;
import dev.holoweb.troupe.net.ClientSync;
import dev.holoweb.troupe.net.Protocol;
import dev.holoweb.troupe.net.Protocol.Manifest;
import dev.holoweb.troupe.net.ResourceInfo;
import net.fabricmc.api.ClientModInitializer;
//? if >=1.20.5 {
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
//?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Client side: downloads the server's resources into the (encrypted) cache while the connection is configuring (or,
 * before 1.20.2, when the server pushes them on join), runs them while in the world.
 */
public final class TroupeClient implements ClientModInitializer {
    private static final Path CACHE = FabricLoader.getInstance().getGameDir().resolve("troupe/blobs");
    private static final ResourceHost host = new ResourceHost(Side.CLIENT, task -> Minecraft.getInstance().execute(task));
    private static final ClientApi api = new ClientApi(host.modules());
    private static final ClientAssets assets = new ClientAssets(CACHE);
    private static final ClientHud hud = new ClientHud(host.modules(), host);

    // Written on the Netty thread (Fabric runs configuration receivers there), read from the render thread.
    private static volatile ClientSync sync;
    /** Resources of the server we are on (empty when it has none). */
    private static volatile Manifest manifest = Manifest.EMPTY;
    /** The play connection to the server; render thread only. */
    private static Link link;
    /** The server said START on this connection (render thread). */
    private static boolean started;
    /** Files are in, assets are loading: what the loading screen says. */
    private static volatile boolean loadingAssets;

    public static Manifest manifest() {
        return manifest;
    }

    @Override
    public void onInitializeClient() {
        Troupe.registerModules(host.modules());
        deleteTree(CACHE.resolveSibling("cache")); // the unencrypted cache of Troupe 0.1
        ClientAssets.deleteBuilt(CACHE);
        if (FabricLoader.getInstance().isModLoaded("vitrine")) host.setUi(VitrineUi.create());
        else Troupe.LOG.info("Vitrine isn't installed: resource pages (ui.*) won't open");

        //? if >=1.20.5 {
        ClientConfigurationConnectionEvents.INIT.register((handler, client) -> {
            reset();
            manifest = Manifest.EMPTY;
            ClientAssets.deleteBuilt(CACHE); // the last server's packs were popped when we left it
        });
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> {
            reset();
            client.execute(assets::clear);
        });

        // The player stays in configuration until READY: files in the cache and assets loaded.
        ClientConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            if (sync == null) {
                sync = new ClientSync(CACHE, m -> ctx.responseSender().sendPacket(new TroupePayload(m)));
                Minecraft.getInstance().execute(() -> showLoading(() -> sync, () -> ctx.responseSender().disconnect(CANCELLED)));
            }
            ClientSync s = sync;
            try {
                Manifest done = s.receive(payload.data());
                if (done != null) {
                    Troupe.LOG.info("{} resources ready ({} files downloaded, {} KiB): {}", done.resources().size(),
                            s.downloadedFiles(), s.downloadedBytes() >> 10, done.resources().keySet());
                    manifest = done;
                    loadingAssets = true;
                    sync = null;
                    Minecraft.getInstance().execute(() -> finishLoading(done, s::ready));
                }
            } catch (IOException e) {
                Troupe.LOG.warn("Resource download failed", e);
                reset();
                ctx.responseSender().disconnect(Component.translatableWithFallback("troupe.disconnect.sync_failed", "Troupe: %s", e.getMessage()));
            }
        });
        //?}

        // INIT comes before any play packet, and its task is queued ahead of their handling on the render thread.
        // The resources start when the server says START (after it sent the global state).
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> {
            started = false;
            //? if <1.20.5 {
            /*manifest = Manifest.EMPTY; // no configuration phase: the manifest comes in play
            ClientAssets.deleteBuilt(CACHE);
            *///?}
            link = new Link(host, new Link.Out() {
                public void send(byte[] message, Runnable onSent) {
                    ClientPlayNetworking.send(new TroupePayload(message));
                    if (onSent != null) onSent.run();
                }

                public void disconnect(String reason) {
                    handler.getConnection().disconnect(Component.literal(reason));
                }
            }, TroupeClient::startResources, CACHE, TroupeClient::applyManifest);
            loadingAssets = false;
            host.setNetwork(link);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (link != null) link.close();
            link = null;
            host.stopAll();
            assets.clear();
            host.setNetwork(null);
            host.resetState();
        }));
        //? if >=1.20.5 {
        ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> receive(payload, ctx.player()));
        //?} else
        /*ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, player, sender) -> receive(payload, player));*/
        //? if >=1.21.6 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("troupe", "hud"), (gui, delta) -> hud.draw(gui));
        //?} else
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((gui, delta) -> hud.draw(gui));*/
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            api.tick();
            host.tick();
        });
    }

    private static void receive(TroupePayload payload, LocalPlayer player) {
        if (link == null) return;
        try {
            link.receive(payload.data());
            // Before 1.20.2 the first download happens here, in the world: hold the player on the loading screen.
            if (!started && link.download() != null && !(ClientCompat.screen() instanceof LoadingScreen)) {
                Link l = link;
                showLoading(l::download, () -> player.connection.getConnection().disconnect(CANCELLED));
            }
        } catch (IOException e) {
            Troupe.LOG.warn("Troupe protocol error from the server: {}", e.getMessage());
            player.connection.getConnection().disconnect(Component.literal("Troupe: " + e.getMessage()));
        }
    }

    private static final Component CANCELLED = Component.translatableWithFallback("troupe.loading.cancelled", "Cancelled");

    private static void showLoading(Supplier<ClientSync> download, Runnable cancel) {
        if (ClientCompat.screen() instanceof LoadingScreen) return;
        ClientCompat.setScreen(new LoadingScreen(ClientCompat.screen(), () -> status(download.get()), () -> {
            ClientSync s = download.get();
            return loadingAssets || s == null || s.wantedBytes() == 0 ? -1 : (double) s.downloadedBytes() / s.wantedBytes();
        }, cancel));
    }

    private static Component status(ClientSync s) {
        if (loadingAssets) return Component.translatableWithFallback("troupe.loading.assets", "Loading textures and sounds");
        if (s == null || s.wantedFiles() == 0) return Component.translatableWithFallback("troupe.loading.checking", "Checking files");
        return Component.translatableWithFallback("troupe.loading.download", "Downloading %s of %s files (%s / %s)",
                s.downloadedFiles(), s.wantedFiles(), size(s.downloadedBytes()), size(s.wantedBytes()));
    }

    private static String size(long bytes) {
        return bytes < 1 << 20 ? (bytes >> 10) + " KiB" : String.format("%.1f MiB", bytes / 1048576.0);
    }

    /** Files are in: load the assets, then READY lets the player in; the loading screen gives way. */
    private static void finishLoading(Manifest m, Runnable ready) {
        loadingAssets = true;
        whenLoaded(m, () -> {
            loadingAssets = false;
            ready.run();
            if (ClientCompat.screen() instanceof LoadingScreen l) ClientCompat.setScreen(l.previous);
        });
    }

    /** {@code then} on the render thread once the assets of {@code m} are in (or after 2 min, so a stuck reload can't hold the player). */
    private static void whenLoaded(Manifest m, Runnable then) {
        CompletableFuture<Void> f = assets.apply(m).completeOnTimeout(null, 2, TimeUnit.MINUTES);
        f.whenComplete((v, e) -> {
            if (e != null) Troupe.LOG.warn("Loading resource assets failed", e);
            Minecraft.getInstance().execute(then);
        });
    }

    /** Starts the server's resources from the cache: only their client/ and shared/ files are reachable. */
    private static void startResources() {
        started = true;
        Manifest m = manifest;
        host.apply(ResourceHost.entries(m, new BlobCache(CACHE, m.key())), m.generations());
        assets.apply(m);
    }

    /**
     * A pushed manifest is fully in the cache: the server's running resources changed while we're in the world, or
     * (before 1.20.2) the first download is done, and START follows.
     */
    private static void applyManifest(Manifest m, Runnable ready) {
        manifest = m;
        if (!started) {
            finishLoading(m, ready);
            return;
        }
        Troupe.LOG.info("Server resources changed: {}", m.generations());
        host.apply(ResourceHost.entries(m, new BlobCache(CACHE, m.key())), m.generations());
        whenLoaded(m, ready);
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            Troupe.LOG.debug("Couldn't delete {}: {}", dir, e.toString());
        }
    }

    private static void reset() {
        ClientSync s = sync;
        if (s != null) s.close();
        sync = null;
    }
}

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
import java.util.List;
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
    private static final WorldDraw world = new WorldDraw(host.modules(), host);
    private static ClientKeys keys;
    private static ClientInteract interact;

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
    /** The server being joined while configuring, when {@code getCurrentServer()} can't see it yet. */
    private static volatile net.minecraft.client.multiplayer.ServerData joining;

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
            gate = new Gate();
            joining = ((dev.holoweb.troupe.mixin.CommonListenerAccess) (Object) handler).troupe$serverData();
            manifest = Manifest.EMPTY;
            ClientAssets.deleteBuilt(CACHE); // the last server's packs were popped when we left it
        });
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> {
            reset();
            if (!manifest.resources().isEmpty()) QuietOverlay.request();
            client.execute(assets::clear);
        });

        // The player stays in configuration until READY: files in the cache and assets loaded.
        ClientConfigurationNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            var sender = ctx.responseSender();
            Runnable cancel = () -> sender.disconnect(CANCELLED);
            Gate g = gate;
            if (g.let(payload.data(), m -> Minecraft.getInstance().execute(() -> showJoin(m, () -> sync, cancel,
                    () -> g.open().forEach(held -> configMessage(held, sender))))))
                configMessage(payload.data(), sender);
        });
        //?}

        // INIT comes before any play packet, and its task is queued ahead of their handling on the render thread.
        // The resources start when the server says START (after it sent the global state).
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> {
            started = false;
            //? if <1.20.5 {
            /*manifest = Manifest.EMPTY; // no configuration phase: the manifest comes in play
            gate = new Gate();
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Right away: before 1.20.2 the game drops the server pack as it leaves.
            if (!manifest.resources().isEmpty()) QuietOverlay.request();
            client.execute(() -> {
                join = null;
                if (link != null) link.close();
                link = null;
                host.stopAll();
                assets.clear();
                hud.clear();
                keys.clear();
                interact.clear();
                world.clear();
                RenderFx.reset();
                host.setNetwork(null);
                host.resetState();
            });
        });
        //? if >=1.20.5 {
        ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> receive(payload, ctx.player()));
        //?} else
        /*ClientPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, player, sender) -> receive(payload, player));*/
        RenderFx.register(host.modules(), host);
        host.setVersion(Troupe.VERSION);
        host.onFault((name, why) -> {
            var player = Minecraft.getInstance().player;
            if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("troupe.fault", name));
        });
        //? if >=26.1 {
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(world::render);
        //?} else
        /*net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(world::render);*/
        //? if >=1.21.6 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("troupe", "hud"), (gui, delta) -> hud.draw(gui));
        //?} else
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((gui, delta) -> hud.draw(gui));*/
        Troupe.worldAllowed = world -> lines(TRUSTED_WORLDS).contains(world);
        ClientKeys.init();
        keys = new ClientKeys(host.modules(), host, CACHE.resolveSibling("keys.json"));
        interact = new ClientInteract(host, hud);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            api.tick();
            keys.tick();
            interact.tick(client);
            host.tick();
            askAboutWorld(client);
        });
    }

    //? if >=1.20.5 {
    /** One message of the configuration-phase download (Netty thread, or the render thread for held ones). */
    private static void configMessage(byte[] data, net.fabricmc.fabric.api.networking.v1.PacketSender sender) {
        if (sync == null) {
            ClientSync[] me = new ClientSync[1];
            sync = me[0] = new ClientSync(CACHE, m -> sender.sendPacket(new TroupePayload(m)),
                    fetched -> Minecraft.getInstance().execute(() -> {
                        if (sync == me[0]) configMessage(fetched, sender);
                    }));
            Minecraft.getInstance().execute(() -> showJoin(null, () -> sync, () -> sender.disconnect(CANCELLED), () -> {}));
        }
        ClientSync s = sync;
        try {
            Manifest done = s.receive(data);
            if (done != null) {
                Troupe.LOG.info("{} resources ready ({} files downloaded, {} over HTTP, {} KiB): {}", done.resources().size(),
                        s.downloadedFiles(), s.httpFiles(), s.downloadedBytes() >> 10, done.resources().keySet());
                manifest = done;
                loadingAssets = true;
                sync = null;
                Minecraft.getInstance().execute(() -> finishLoading(done, s::ready));
            }
        } catch (IOException e) {
            Troupe.LOG.warn("Resource download failed", e);
            reset();
            sender.disconnect(Component.translatableWithFallback("troupe.disconnect.sync_failed", "Troupe: %s", e.getMessage()));
        }
    }
    //?}

    private static void receive(TroupePayload payload, LocalPlayer player) {
        if (link == null) return;
        Link l = link;
        Runnable cancel = () -> player.connection.getConnection().disconnect(CANCELLED);
        Gate g = gate;
        if (g.let(payload.data(), m -> showJoin(m, l::download, cancel, () -> g.open().forEach(held -> playMessage(held, player)))))
            playMessage(payload.data(), player);
    }

    private static void playMessage(byte[] data, LocalPlayer player) {
        if (link == null) return;
        try {
            link.receive(data);
            // Before 1.20.2 the first download happens here, in the world: hold the player on the join window.
            if (!started && link.download() != null && join == null) {
                Link l = link;
                showJoin(null, l::download, () -> player.connection.getConnection().disconnect(CANCELLED), () -> {});
            }
        } catch (IOException e) {
            Troupe.LOG.warn("Troupe protocol error from the server: {}", e.getMessage());
            player.connection.getConnection().disconnect(Component.literal("Troupe: " + e.getMessage()));
        }
    }

    private static final Component CANCELLED = Component.translatableWithFallback("troupe.loading.cancelled", "Cancelled");

    /** The join window of this connection (render thread), from the first Troupe packet until the player is in. */
    private static JoinScreen join;

    /** Opens the join window (once per connection): asking about {@code asking} first, or loading right away when null. */
    private static void showJoin(Manifest asking, Supplier<ClientSync> download, Runnable cancel, Runnable onJoin) {
        if (join != null) return;
        join = new JoinScreen(ClientCompat.screen(), asking, () -> status(download.get()), () -> {
            ClientSync s = download.get();
            return loadingAssets || s == null || s.wantedBytes() == 0 ? -1 : (double) s.downloadedBytes() / s.wantedBytes();
        }, cancel, onJoin);
        ClientCompat.setScreen(join);
    }

    /** This connection's gate (a new one per connection). */
    private static volatile Gate gate = new Gate();

    /**
     * Holds a connection's Troupe messages until the player says Join, the first time on a server with resources:
     * the download only starts when the client answers the MANIFEST, so nothing is fetched before that.
     */
    private static final class Gate {
        private List<byte[]> held;
        private boolean decided, open;

        /** @return true to handle {@code message} now; otherwise it's held, and the first one held calls {@code ask} */
        synchronized boolean let(byte[] message, java.util.function.Consumer<Manifest> ask) {
            if (open) return true;
            if (!decided) {
                decided = true;
                Manifest m = peek(message);
                if (m == null || m.resources().isEmpty() || !mustAsk()) {
                    open = true;
                    return true;
                }
                held = new java.util.ArrayList<>();
                held.add(message);
                ask.accept(m);
                return false;
            }
            held.add(message);
            return false;
        }

        /** Join: lets everything through; returns what was held, in order. */
        synchronized List<byte[]> open() {
            open = true;
            List<byte[]> h = held == null ? List.of() : held;
            held = null;
            return h;
        }

        private static Manifest peek(byte[] message) {
            try {
                Protocol.In in = new Protocol.In(message);
                return in.type() == Protocol.MANIFEST ? Protocol.decodeManifest(in) : null;
            } catch (IOException | RuntimeException e) {
                return null; // the download will say what's wrong
            }
        }
    }

    private static Component status(ClientSync s) {
        if (loadingAssets) return Component.translatableWithFallback("troupe.loading.assets", "Loading textures and sounds");
        if (s == null || s.wantedFiles() == 0) return Component.translatableWithFallback("troupe.loading.checking", "Checking files");
        return Component.translatableWithFallback("troupe.loading.download", "Downloading %s of %s files (%s / %s)",
                s.downloadedFiles(), s.wantedFiles(), Panel.size(s.downloadedBytes()), Panel.size(s.wantedBytes()));
    }

    /** Files are in: load the assets, then READY lets the player in; the join window gives way. */
    private static void finishLoading(Manifest m, Runnable ready) {
        loadingAssets = true;
        whenLoaded(m, () -> {
            loadingAssets = false;
            JoinScreen window = join;
            join = null;
            ready.run();
            if (window != null && ClientCompat.screen() == window) restore(window.previous);
        });
    }

    private static void restore(net.minecraft.client.gui.screens.Screen screen) {
        ClientCompat.setScreen(screen);
        //? if <1.20.2 {
        /*// The game only tells "Loading terrain" the world is in while it is the open screen; it wasn't (ours was), so tell it now.
        if (screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen r) r.loadingPacketsReceived();
        *///?}
    }

    private static final Path TRUSTED = CACHE.resolveSibling("trusted_servers.txt");
    private static final Path TRUSTED_WORLDS = CACHE.resolveSibling("trusted_worlds.txt");
    private static String askedWorld;

    /**
     * A map that brings resources: once the player is in the world with no screen open, asks whether its scripts
     * may run (once per visit; "Allow" is remembered by the world's folder name). Until then nothing of it runs.
     */
    // ponytail: trust is by folder name; hash the resources if a map swapped in place becomes a concern
    private static void askAboutWorld(Minecraft client) {
        String world = Troupe.pendingWorld();
        if (world == null) {
            askedWorld = null;
            return;
        }
        if (world.equals(askedWorld) || client.player == null || ClientCompat.screen() != null) return;
        askedWorld = world;
        ClientCompat.setScreen(new CardConfirm(ServerApi.id("minecraft:textures/item/writable_book.png"),
                Component.translatableWithFallback("troupe.world.title", "This world runs scripts"),
                Component.translatableWithFallback("troupe.world.question",
                        "This world brings %s resources of its own. Their scripts run in your game and can change this world. Allow them?", Troupe.pendingResources()),
                Component.translatableWithFallback("troupe.world.allow", "Allow"),
                Component.translatableWithFallback("troupe.assets.skip", "Not now"), yes -> {
            ClientCompat.setScreen(null);
            if (!yes) return;
            var all = lines(TRUSTED_WORLDS);
            all.add(world);
            saveLines(TRUSTED_WORLDS, all);
            Troupe.allowWorld();
        }));
    }

    /** Whether joining this server needs the player's word (singleplayer never does). */
    private static boolean mustAsk() {
        if (Minecraft.getInstance().isLocalServer()) return false;
        var server = server();
        return server == null || !trusted(server.ip);
    }

    /** The server being joined or played on (null in singleplayer). */
    static net.minecraft.client.multiplayer.ServerData server() {
        var current = Minecraft.getInstance().getCurrentServer();
        return current != null ? current : joining;
    }

    private static String address(String ip) {
        return ip.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The player allowed the scripts of the server at {@code ip}. */
    static boolean trusted(String ip) {
        return trustedSet().contains(address(ip));
    }

    /** The player said Join: the server being joined won't ask again. */
    static void trustCurrent() {
        var server = server();
        if (server == null || trusted(server.ip)) return;
        var all = trustedSet();
        all.add(address(server.ip));
        saveTrusted(all);
    }

    /** Asks again next time on the server at {@code ip}. */
    static void forget(String ip) {
        var all = trustedSet();
        if (all.remove(address(ip))) saveTrusted(all);
    }

    private static java.util.Set<String> trustedSet() {
        return lines(TRUSTED);
    }

    private static void saveTrusted(java.util.Set<String> all) {
        saveLines(TRUSTED, all);
    }

    private static java.util.Set<String> lines(Path file) {
        try {
            return Files.exists(file) ? new java.util.TreeSet<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new java.util.TreeSet<>();
        } catch (IOException e) {
            Troupe.LOG.warn("Couldn't read {}", file, e);
            return new java.util.TreeSet<>();
        }
    }

    private static void saveLines(Path file, java.util.Set<String> all) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, all, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Troupe.LOG.warn("Couldn't save {}", file, e);
        }
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
        Minecraft.getInstance().execute(() -> join = null);
        ClientSync s = sync;
        if (s != null) s.close();
        sync = null;
    }
}

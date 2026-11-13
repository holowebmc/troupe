package dev.holoweb.troupe;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.holoweb.troupe.api.Modules;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.api.TroupeModule;
import dev.holoweb.troupe.lua.Link;
import dev.holoweb.troupe.lua.Links;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ProtocolException;
import dev.holoweb.troupe.net.ResourceInfo;
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side (dedicated or integrated): runs the resources in {@code resources/} and sends their client files
 * (during configuration where it exists, see {@link ConfigPhase}; else on join).
 */
public final class Troupe implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("troupe");
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("troupe")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);

    private static volatile MinecraftServer server;
    private static final ResourceHost host = new ResourceHost(Side.SERVER, task -> {
        MinecraftServer s = server;
        if (s != null) s.execute(task);
    });
    private static final Links links = new Links(host);
    private static final ServerApi api = new ServerApi(host.modules(), host, () -> server);
    private static final dev.holoweb.troupe.lua.DbApi db = new dev.holoweb.troupe.lua.DbApi(host.modules());
    /** Every resource on disk (last scan). */
    private static volatile ServerResources resources = ServerResources.EMPTY;
    /** What clients run: the running resources with client files, with their generations. */
    private static volatile ServerResources published = ServerResources.EMPTY;
    /** What each player got during configuration, to catch up if it changed before they reached play. */
    private static final Map<java.util.UUID, ServerResources> configured = new ConcurrentHashMap<>();
    /** Players still on their first download in play (no configuration phase). */
    private static final Map<java.util.UUID, Link> downloading = new ConcurrentHashMap<>();
    /** -Dtroupe.dev=true: watch resources/ and restart what changes on disk. */
    private static final boolean DEV = Boolean.getBoolean("troupe.dev");
    private static Map<String, Long> devStamps;

    @Override
    public void onInitialize() {
        ConfigPhase.register(() -> published, configured);
        TroupeStatus.serve(() -> published.manifest());
        host.setNetwork(links);
        host.setVersion(VERSION);
        // entity.state / player.public go to who has the entity in sight (the game's own entity tracking).
        host.setEntityWatchers(Troupe::watchers);
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register(
                (entity, player) -> host.showEntity(entity.getStringUUID(), player.getStringUUID()));
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.STOP_TRACKING.register(
                (entity, player) -> host.hideEntity(entity.getStringUUID(), player.getStringUUID()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            var reason = entity.getRemovalReason();
            if (reason == null || !reason.shouldDestroy()) return;
            host.entityGone(entity.getStringUUID());
            Instances.forget(entity.getUUID());
        });
        host.onFault((name, why) -> publish()); // connected clients stop it too
        registerModules(host.modules());

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            config = readConfig();
            host.setJavaAllow(javaAllow());
            startDownloads();
            openDatabase(s);
            rescan(s);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            Path world = Compat.worldWithResources(s);
            String name = world == null ? null : world.getFileName().toString();
            if (name != null && !resources.infos().isEmpty() && !worldAllowed.test(name)) {
                pendingWorld = name; // the client asks once the player is in; allowWorld() starts them
                LOG.info("The world '{}' brings {} resources; waiting for the player to allow its scripts", name, resources.infos().size());
            } else {
                host.startAll();
                publish();
            }
            if (DEV) LOG.info("Dev mode: resources restart when their files change");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            host.stopAll();
            db.close(); // after the resources: what their on_stop asked for is written first
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            pendingWorld = null;
            api.stopped();
            if (blobServer != null) blobServer.close();
            blobServer = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            host.tick();
            api.tick();
            if (DEV && s.getTickCount() % 20 == 0) devCheck(s);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(command()));

        // Play phase: one Link per player with Troupe. Play receivers run on the server thread.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.player;
            ServerResources got = configured.remove(player.getUUID());
            if (ServerPlayNetworking.canSend(player, TroupePayload.TYPE)) {
                Link link = new Link(host, ServerApi.peer(player), out(player));
                ServerResources now = published;
                if (got == null && !now.manifest().resources().isEmpty()) {
                    // No configuration phase (before 1.20.2): the first download happens in play, and the player
                    // joins (global state, START, on_join) once it's in, as they would after configuration.
                    downloading.put(player.getUUID(), link);
                    link.push(now, () -> {
                        downloading.remove(player.getUUID());
                        links.add(link);
                        if (!published.manifest().equals(now.manifest())) link.push(published);
                        link.whenHello(() -> api.joined(player));
                    });
                    return;
                }
                links.add(link);
                if (!now.manifest().equals((got == null ? ServerResources.EMPTY : got).manifest())) link.push(now);
                // on_join once the client answered START: by then scripts see its mod modules too
                link.whenHello(() -> api.joined(player));
                return;
            } else if (!published.manifest().resources().isEmpty()) {
                handler.disconnect(Component.translatableWithFallback("troupe.disconnect.missing_mod", "This server needs the Troupe mod."));
                return;
            }
            api.joined(player); // queued: runs after START went out, so state set by players.on_join reaches the client
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            java.util.UUID uuid = handler.player.getUUID();
            if (downloading.containsKey(uuid)) { // never joined: no on_leave
                s.execute(() -> {
                    Link d = downloading.remove(uuid);
                    if (d != null) d.close();
                });
                return;
            }
            api.left(handler.player); // queued before the removal below: on_leave still sees the player's state
            String id = handler.player.getUUID().toString();
            s.execute(() -> links.remove(id));
        });
        //? if >=1.20.5 {
        ServerPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> receive(payload, ctx.player()));
        //?} else
        /*ServerPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, player, sender) -> receive(payload, player));*/
    }

    private static void receive(TroupePayload payload, ServerPlayer player) {
        Link link = links.get(player.getUUID().toString());
        if (link == null) link = downloading.get(player.getUUID());
        if (link == null) return;
        try {
            link.receive(payload.data());
        } catch (IOException e) {
            LOG.warn("Troupe protocol error from {}: {}", Compat.name(player.getGameProfile()), e.getMessage());
            player.connection.disconnect(Component.literal("Troupe: " + e.getMessage()));
        }
    }

    /**
     * Maps: whether the player already allowed the scripts of the world in this folder. The client sets it; a dedicated
     * server has nobody to ask, and its resources are its owner's.
     */
    static volatile java.util.function.Predicate<String> worldAllowed = world -> true;
    private static volatile String pendingWorld;

    /** The world whose resources wait for the player's word (its folder name), or null. */
    static String pendingWorld() {
        return pendingWorld;
    }

    /** The client mod modules the waiting world's resources list. */
    static java.util.List<String> pendingModules() {
        return resources.manifest().modules();
    }

    static int pendingResources() {
        return resources.infos().size();
    }

    /** The player said yes: the world's resources start, and the connected client gets them like any restart. */
    static void allowWorld() {
        MinecraftServer s = server;
        if (s == null || pendingWorld == null) return;
        pendingWorld = null;
        s.execute(() -> {
            host.startAll();
            publish();
        });
    }

    private static java.util.Collection<String> watchers(String entityId) {
        MinecraftServer s = server;
        if (s == null) return java.util.List.of();
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            return java.util.List.of();
        }
        for (var level : s.getAllLevels()) {
            var entity = level.getEntity(uuid);
            if (entity == null) continue;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (ServerPlayer p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(entity)) out.add(p.getStringUUID());
            if (entity instanceof ServerPlayer) out.add(entityId);
            return out;
        }
        return java.util.List.of();
    }

    /**
     * {@code dir/stubs/}: everything an editor (LuaLS) needs to know this installation. Troupe's own API (the files
     * in the jar's {@code /troupe/defs}), what the mods of this side added ({@code mods-<side>.lua}) and what the
     * running resources offer each other ({@code resources-<side>.lua}). Returns the folder, or null on failure.
     */
    static java.nio.file.Path writeStubs(java.nio.file.Path dir, ResourceHost host) {
        java.nio.file.Path stubs = dir.resolve("stubs");
        String side = host.side().folder();
        try {
            java.nio.file.Files.createDirectories(stubs);
            for (String def : new String[]{"shared", "server", "client"}) {
                try (var in = Troupe.class.getResourceAsStream("/troupe/defs/" + def + ".lua")) {
                    if (in != null) java.nio.file.Files.write(stubs.resolve(def + ".lua"), in.readAllBytes());
                }
            }
            java.nio.file.Files.writeString(stubs.resolve("mods-" + side + ".lua"), host.modules().stubs());
            java.nio.file.Files.writeString(stubs.resolve("resources-" + side + ".lua"), host.resourceStubs());
            return stubs;
        } catch (java.io.IOException e) {
            LOG.warn("Couldn't write the Lua definitions to {}", stubs, e);
            return null;
        }
    }

    /** Calls the {@code troupe} entrypoint and the side-specific one with this side's module registry. */
    static void registerModules(Modules modules) {
        // Game types mods hand over as they are (ServerApi adds players, entities and items on the server).
        if (modules instanceof dev.holoweb.troupe.lua.ModuleRegistry names) {
            names.luaName(net.minecraft.world.phys.Vec3.class, "Pos");
            names.luaName(net.minecraft.core.BlockPos.class, "Pos");
            names.luaName(net.minecraft.network.chat.Component.class, "string");
            names.luaName(net.minecraft.resources.Identifier.class, "string");
            names.luaName(net.minecraft.world.entity.Entity.class, "Entity");
            names.luaName(net.minecraft.world.entity.player.Player.class, "Player");
            names.luaName(net.minecraft.world.item.ItemStack.class, "table");
        }
        modules.convert(net.minecraft.world.phys.Vec3.class, v -> java.util.Map.of("x", v.x, "y", v.y, "z", v.z), o -> {
            double[] p = dev.holoweb.troupe.lua.Zones.point(o);
            return new net.minecraft.world.phys.Vec3(p[0], p[1], p[2]);
        });
        modules.convert(net.minecraft.core.BlockPos.class, v -> java.util.Map.of("x", v.getX(), "y", v.getY(), "z", v.getZ()), o -> {
            double[] p = dev.holoweb.troupe.lua.Zones.point(o);
            return net.minecraft.core.BlockPos.containing(p[0], p[1], p[2]);
        });
        modules.convert(net.minecraft.network.chat.Component.class, net.minecraft.network.chat.Component::getString,
                o -> o instanceof String s ? net.minecraft.network.chat.Component.literal(s) : null);
        modules.convert(net.minecraft.resources.Identifier.class, Object::toString,
                o -> o instanceof String s ? net.minecraft.resources.Identifier.tryParse(s) : null);
        String sideEntrypoint = modules.side() == Side.CLIENT ? "troupe-client" : "troupe-server";
        for (String entrypoint : new String[]{"troupe", sideEntrypoint}) {
            for (var container : FabricLoader.getInstance().getEntrypointContainers(entrypoint, TroupeModule.class)) {
                try {
                    container.getEntrypoint().register(modules);
                } catch (RuntimeException e) {
                    LOG.error("{} failed to register its Troupe modules", container.getProvider().getMetadata().getId(), e);
                }
            }
        }
    }

    /**
     * Rereads {@code resources/}: what the server can start and what joining clients download.
     * ponytail: rehashes every client file each time; cache hashes by mtime if resources get big.
     */
    private static void rescan(MinecraftServer s) {
        Path root = Compat.serverDir(s).resolve("resources");
        try {
            resources = ServerResources.scan(root, w -> LOG.warn("Resource {}", w)).withKey(cacheKey(s)).withDownload(downloadUrl);
        } catch (IOException e) {
            LOG.error("Could not read the resources folder", e);
            resources = ServerResources.EMPTY.withKey(cacheKey(s));
        }
        Map<String, ResourceHost.Entry> entries = ResourceHost.entries(resources);
        host.setAvailable(entries);
        LOG.info("{} resources, {} with client files: {}", entries.size(), resources.manifest().resources().size(), entries.keySet());
    }

    /**
     * The key clients encrypt this server's files with ({@code troupe/cache.key}, made once). Keeping it means
     * returning players reuse their cache; deleting it makes everyone download again.
     */
    private static String cacheKey(MinecraftServer s) {
        Path file = Compat.serverDir(s).resolve("troupe/cache.key");
        try {
            if (Files.isRegularFile(file)) {
                String key = Files.readString(file).trim();
                if (key.matches("[0-9a-f]{64}")) return key;
                LOG.warn("{} isn't a key; making a new one", file);
            }
            String key = dev.holoweb.troupe.net.BlobCache.newKey();
            Files.createDirectories(file.getParent());
            Files.writeString(file, key);
            return key;
        } catch (IOException e) {
            LOG.error("Couldn't keep the cache key in {}: clients download everything on each restart", file, e);
            return dev.holoweb.troupe.net.BlobCache.newKey();
        }
    }

    /**
     * {@code config/troupe.json} → {@code java.allow}: the packages server resources may use through {@code java.*}.
     * Written with an empty list (off) the first time.
     */
    private static java.util.List<String> javaAllow() {
        try {
            java.util.List<String> allow = new java.util.ArrayList<>();
            if (config.has("java")) for (var e : config.getAsJsonObject("java").getAsJsonArray("allow")) allow.add(e.getAsString());
            if (!allow.isEmpty()) LOG.warn("java.* is on for server resources: {}", allow);
            return allow;
        } catch (RuntimeException e) {
            LOG.error("Bad \"java\" in config/troupe.json; java.* stays off", e);
            return java.util.List.of();
        }
    }

    /** {@code config/troupe.json} (comments allowed), read when the server starts; written with the defaults the first time. */
    private static com.google.gson.JsonObject config = new com.google.gson.JsonObject();
    private static dev.holoweb.troupe.net.BlobServer blobServer;
    private static String downloadUrl = "";

    private static com.google.gson.JsonObject readConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("troupe.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, """
                        {
                          // Packages (or classes) server resources may reach with java.class / java.new, e.g.
                          // "com.simibubi.create.api". Empty: java.* is off. Only for code you trust: it runs as the server.
                          "java": { "allow": [] }

                          // Faster first join: clients fetch the files with GET <url><sha256> and fall back to the game
                          // connection. "port" makes this server answer there itself (open it in the firewall); leave it out
                          // when something else serves the same paths (a CDN pulling from that port, for example).
                          // , "download": { "url": "http://play.example.com:25580/", "port": 25580 }

                          // Where db.query / db.execute keep their data. Left out: SQLite in troupe/data.db. Any JDBC URL
                          // works with its driver's jar in mods/; "driver" is that driver's class.
                          // , "database": { "url": "jdbc:mysql://localhost/troupe", "driver": "com.mysql.cj.jdbc.Driver",
                          //                 "user": "troupe", "password": "..." }
                        }
                        """);
            }
            return com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            LOG.error("Couldn't read {}; using the defaults", file, e);
            return new com.google.gson.JsonObject();
        }
    }

    /** {@code database} in the config, or SQLite in {@code troupe/data.db}. Nothing is opened until a script uses it. */
    private static void openDatabase(MinecraftServer s) {
        Path file = Compat.serverDir(s).resolve("troupe/data.db").toAbsolutePath();
        String url = "jdbc:sqlite:" + file.toString().replace('\\', '/'), driver = null, user = null, password = null;
        try {
            Files.createDirectories(file.getParent());
            if (config.has("database")) {
                var d = config.getAsJsonObject("database");
                url = d.get("url").getAsString();
                if (!url.startsWith("jdbc:")) throw new IllegalArgumentException("database.url must be a JDBC URL (jdbc:...)");
                driver = d.has("driver") ? d.get("driver").getAsString() : null;
                user = d.has("user") ? d.get("user").getAsString() : null;
                password = d.has("password") ? d.get("password").getAsString() : null;
                LOG.info("Database: {}", url.replaceAll("^(jdbc:[a-z0-9]+).*", "$1")); // never the whole URL: it may carry a password
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("Bad \"database\" in config/troupe.json; using SQLite in {}", file, e);
            url = "jdbc:sqlite:" + file.toString().replace('\\', '/');
            driver = user = password = null;
        }
        db.open(url, driver, user, password);
    }

    /** {@code download} in the config: the URL clients are told, and the built-in file server when it has a port. */
    private static void startDownloads() {
        downloadUrl = "";
        try {
            if (!config.has("download")) return;
            var d = config.getAsJsonObject("download");
            String url = d.get("url").getAsString();
            if (!url.endsWith("/")) url += "/";
            if (url.length() < 9 || !dev.holoweb.troupe.net.Protocol.validDownload(url)) throw new IllegalArgumentException("download.url must be an http(s) URL");
            if (d.has("port")) blobServer = new dev.holoweb.troupe.net.BlobServer(d.get("port").getAsInt());
            downloadUrl = url;
            LOG.info("Clients download from {}{}", url, blobServer == null ? "" : " (served here, port " + blobServer.port() + ")");
        } catch (IOException | RuntimeException | LinkageError e) { // LinkageError: a runtime without jdk.httpserver
            LOG.error("Bad \"download\" in config/troupe.json; clients download through the game connection", e);
        }
    }

    /** Tells connected clients when what runs changed (started, stopped, restarted). */
    private static void publish() {
        ServerResources now = resources.only(host.running());
        if (now.manifest().equals(published.manifest())) return;
        published = now;
        if (blobServer != null) blobServer.serve(now);
        links.push(now);
    }

    /** Dev mode, once a second: a resource whose files changed restarts; a new one starts; a deleted one stops. */
    private static void devCheck(MinecraftServer s) {
        Map<String, Long> now = ServerResources.stamps(Compat.serverDir(s).resolve("resources"));
        Map<String, Long> before = devStamps;
        devStamps = now;
        if (before == null || before.equals(now) || pendingWorld != null) return;
        rescan(s);
        java.util.Set<String> names = new java.util.TreeSet<>(now.keySet());
        names.addAll(before.keySet());
        for (String name : names) {
            if (java.util.Objects.equals(before.get(name), now.get(name))) continue;
            try {
                if (!now.containsKey(name) || !host.available().contains(name)) {
                    if (!host.stop(name).isEmpty()) LOG.info("Dev: stopped {}", name);
                } else if (host.isRunning(name)) {
                    host.restart(name);
                    LOG.info("Dev: restarted {}", name);
                } else {
                    host.start(name);
                    LOG.info("Dev: started {}", name);
                }
            } catch (ResourceException e) {
                LOG.error("Dev: {} failed: {}", name, e.getMessage());
            }
        }
        publish();
    }

    private interface Action {
        void run(String resource) throws ResourceException;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("troupe")
                .requires(src -> Compat.permission(src, 3))
                .then(Commands.literal("list").executes(c -> {
                    StringBuilder out = new StringBuilder("Resources:");
                    for (String name : host.available())
                        out.append("\n ").append(host.isRunning(name) ? "● " : "○ ").append(name);
                    c.getSource().sendSystemMessage(Component.literal(out.toString()));
                    return host.available().size();
                }))
                .then(action("start", host::start))
                .then(action("stop", name -> {
                    if (host.stop(name).isEmpty()) throw new ResourceException(name + " isn't running");
                }))
                .then(Commands.literal("stubs").executes(c -> {
                    java.nio.file.Path root = Compat.serverDir(c.getSource().getServer());
                    java.nio.file.Path file = writeStubs(root.resolve("troupe"), host);
                    // Editors that open resources/ find the definitions through this (never overwritten).
                    java.nio.file.Path luarc = root.resolve("resources/.luarc.json");
                    try {
                        if (file != null && java.nio.file.Files.isDirectory(luarc.getParent()) && !java.nio.file.Files.exists(luarc))
                            java.nio.file.Files.writeString(luarc, "{\n  \"runtime.version\": \"Lua 5.2\",\n  \"workspace.library\": [\"../troupe/stubs\"]\n}\n");
                    } catch (java.io.IOException e) {
                        LOG.warn("Couldn't write {}", luarc, e);
                    }
                    c.getSource().sendSystemMessage(Component.literal(file == null ? "Couldn't write the definitions (see the log)" : "Lua definitions written to " + file));
                    return file == null ? 0 : 1;
                }))
                .then(action("restart", host::restart))
                .then(action("ensure", name -> {
                    if (host.isRunning(name)) host.restart(name);
                    else host.start(name);
                }));
    }

    /** Every action rescans first, so edits on disk and new resources are picked up; connected clients follow. */
    private static LiteralArgumentBuilder<CommandSourceStack> action(String verb, Action action) {
        return Commands.literal(verb).then(Commands.argument("resource", StringArgumentType.word())
                .suggests((c, builder) -> SharedSuggestionProvider.suggest(host.available(), builder))
                .executes(c -> run(c, verb, action)));
    }

    private static int run(CommandContext<CommandSourceStack> c, String verb, Action action) {
        String name = StringArgumentType.getString(c, "resource");
        rescan(c.getSource().getServer());
        try {
            action.run(name);
        } catch (ResourceException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        } finally {
            publish();
        }
        c.getSource().sendSuccess(() -> Component.literal(verb + " " + name + ": ok"), true);
        return 1;
    }

    private static Link.Out out(ServerPlayer player) {
        ServerSync.Sender s = sender(ServerPlayNetworking.getSender(player), reason -> player.connection.disconnect(reason));
        return new Link.Out() {
            public void send(byte[] message, Runnable onSent) {
                s.send(message, onSent);
            }

            public void disconnect(String reason) {
                s.disconnect(reason);
            }
        };
    }

    /** {@code disconnect}: PacketSender only has it from 1.20.5. */
    static ServerSync.Sender sender(PacketSender packets, java.util.function.Consumer<Component> disconnect) {
        return new ServerSync.Sender() {
            @Override
            public void send(byte[] message, Runnable onSent) {
                TroupePayload payload = new TroupePayload(message);
                if (onSent == null) packets.sendPacket(payload);
                //? if >=1.20.5 {
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

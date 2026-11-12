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

    private static volatile MinecraftServer server;
    private static final ResourceHost host = new ResourceHost(Side.SERVER, task -> {
        MinecraftServer s = server;
        if (s != null) s.execute(task);
    });
    private static final Links links = new Links(host);
    private static final ServerApi api = new ServerApi(host.modules(), host, () -> server);
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
        registerModules(host.modules());

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            host.setJavaAllow(javaAllow());
            rescan(s);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            host.startAll();
            publish();
            if (DEV) LOG.info("Dev mode: resources restart when their files change");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> host.stopAll());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
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

    /** Calls the {@code troupe} entrypoint and the side-specific one with this side's module registry. */
    static void registerModules(Modules modules) {
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
            resources = ServerResources.scan(root, w -> LOG.warn("Resource {}", w)).withKey(cacheKey(s));
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
        Path file = FabricLoader.getInstance().getConfigDir().resolve("troupe.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, """
                        {
                          // Packages (or classes) server resources may reach with java.class / java.new, e.g.
                          // "com.simibubi.create.api". Empty: java.* is off. Only for code you trust: it runs as the server.
                          "java": { "allow": [] }
                        }
                        """);
            }
            var root = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            java.util.List<String> allow = new java.util.ArrayList<>();
            if (root.has("java")) for (var e : root.getAsJsonObject("java").getAsJsonArray("allow")) allow.add(e.getAsString());
            if (!allow.isEmpty()) LOG.warn("java.* is on for server resources: {}", allow);
            return allow;
        } catch (IOException | RuntimeException e) {
            LOG.error("Couldn't read {}; java.* stays off", file, e);
            return java.util.List.of();
        }
    }

    /** Tells connected clients when what runs changed (started, stopped, restarted). */
    private static void publish() {
        ServerResources now = resources.only(host.running());
        if (now.manifest().equals(published.manifest())) return;
        published = now;
        links.push(now);
    }

    /** Dev mode, once a second: a resource whose files changed restarts; a new one starts; a deleted one stops. */
    private static void devCheck(MinecraftServer s) {
        Map<String, Long> now = ServerResources.stamps(Compat.serverDir(s).resolve("resources"));
        Map<String, Long> before = devStamps;
        devStamps = now;
        if (before == null || before.equals(now)) return;
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

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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Server side (dedicated or integrated): runs the resources in {@code resources/} and sends their client files during
 * configuration.
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
    private static final Map<ServerConfigurationPacketListenerImpl, ServerSync> syncs = new ConcurrentHashMap<>();
    /** What each player got during configuration, to catch up if it changed before they reached play. */
    private static final Map<java.util.UUID, ServerResources> configured = new ConcurrentHashMap<>();
    /** -Dtroupe.dev=true: watch resources/ and restart what changes on disk. */
    private static final boolean DEV = Boolean.getBoolean("troupe.dev");
    private static Map<String, Long> devStamps;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TroupePayload.TYPE, TroupePayload.CODEC);
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

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, s) -> {
            ServerResources current = published;
            configured.put(handler.getOwner().id(), current);
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
            } catch (IOException e) {
                LOG.warn("Troupe protocol error from {}: {}", handler.getOwner().name(), e.getMessage());
                handler.disconnect(Component.literal("Troupe: " + e.getMessage()));
            }
        });

        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerSync sync = syncs.remove(handler);
            if (sync != null) sync.close();
        });

        // Play phase: one Link per player with Troupe. Play receivers run on the server thread.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.player;
            ServerResources got = configured.remove(player.getUUID());
            if (ServerPlayNetworking.canSend(player, TroupePayload.TYPE)) {
                Link link = new Link(host, ServerApi.peer(player), out(ServerPlayNetworking.getSender(player)));
                links.add(link);
                ServerResources now = published;
                if (!now.manifest().equals((got == null ? ServerResources.EMPTY : got).manifest())) link.push(now);
            }
            api.joined(player); // queued: runs after START went out, so state set by players.on_join reaches the client
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            api.left(handler.player); // queued before the removal below: on_leave still sees the player's state
            String id = handler.player.getUUID().toString();
            s.execute(() -> links.remove(id));
        });
        ServerPlayNetworking.registerGlobalReceiver(TroupePayload.TYPE, (payload, ctx) -> {
            Link link = links.get(ctx.player().getUUID().toString());
            if (link == null) return;
            try {
                link.receive(payload.data());
            } catch (IOException e) {
                LOG.warn("Troupe protocol error from {}: {}", ctx.player().getGameProfile().name(), e.getMessage());
                ctx.player().connection.disconnect(Component.literal("Troupe: " + e.getMessage()));
            }
        });
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
        Path root = s.getServerDirectory().resolve("resources");
        try {
            resources = ServerResources.scan(root, w -> LOG.warn("Resource {}", w));
        } catch (IOException e) {
            LOG.error("Could not read the resources folder", e);
            resources = ServerResources.EMPTY;
        }
        Map<String, ResourceHost.Entry> entries = ResourceHost.entries(resources);
        host.setAvailable(entries);
        LOG.info("{} resources, {} with client files: {}", entries.size(), resources.manifest().resources().size(), entries.keySet());
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
        Map<String, Long> now = ServerResources.stamps(s.getServerDirectory().resolve("resources"));
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
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
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

    private static Link.Out out(PacketSender packets) {
        ServerSync.Sender s = sender(packets);
        return new Link.Out() {
            public void send(byte[] message, Runnable onSent) {
                s.send(message, onSent);
            }

            public void disconnect(String reason) {
                s.disconnect(reason);
            }
        };
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

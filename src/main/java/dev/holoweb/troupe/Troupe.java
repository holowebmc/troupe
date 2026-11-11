package dev.holoweb.troupe;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.holoweb.troupe.api.Modules;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.api.TroupeModule;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ProtocolException;
import dev.holoweb.troupe.net.ResourceInfo;
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
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
    private static volatile ServerResources resources = ServerResources.EMPTY;
    private static final Map<ServerConfigurationPacketListenerImpl, ServerSync> syncs = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(TroupePayload.TYPE, TroupePayload.CODEC);
        registerModules(host.modules());

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            rescan(s);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> host.startAll());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> host.stopAll());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(command()));

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, s) -> {
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

        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerSync sync = syncs.remove(handler);
            if (sync != null) sync.close();
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
        Map<String, ResourceHost.Entry> entries = new TreeMap<>();
        for (ResourceInfo info : resources.infos().values()) {
            Path dir = resources.root().resolve(info.name());
            // Paths come from LuaRuntime's require, which only builds server/... and shared/... names.
            entries.put(info.name(), new ResourceHost.Entry(info, path -> {
                Path file = dir.resolve(path);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }));
        }
        host.setAvailable(entries);
        LOG.info("{} resources, {} with client files: {}", entries.size(), resources.manifest().resources().size(), entries.keySet());
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

    /** Every action rescans first, so edits on disk and new resources are picked up. Clients get them on their next join. */
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
        }
        c.getSource().sendSuccess(() -> Component.literal(verb + " " + name + ": ok"), true);
        return 1;
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

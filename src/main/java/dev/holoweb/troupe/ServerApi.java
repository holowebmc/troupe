package dev.holoweb.troupe;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.holoweb.troupe.api.Args;
import dev.holoweb.troupe.api.Callback;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.lua.ModuleRegistry;
import dev.holoweb.troupe.lua.Peer;
import dev.holoweb.troupe.lua.ResourceHost;
import dev.holoweb.troupe.lua.Store;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Troupe's server API, as global modules (see the prelude for the player methods):
 * <pre>
 * players.list()  players.get(id|name)  players.on_join(fn(player))  players.on_leave(fn(player))
 *   (on_join also runs for every player already online when the resource starts or restarts)
 *   player:message(text)  player:pos()  player:teleport(x, y, z, dim?)  player:give(item, count?)  player:online()
 * chat.broadcast(text)  chat.on_message(fn(player, text))
 * command.register(name, fn(player|nil, args), { admin = false })
 * world.block(x, y, z, dim?) → id | nil    world.set_block(x, y, z, id, dim?) → ok   (loaded chunks only)
 * store.get(key)  store.set(key, value)  store.delete(key)  store.keys()     (per resource, kept on disk)
 * </pre>
 * Server thread only, like everything a module function does.
 */
final class ServerApi {
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]{1,32}");

    private record Command(String resource, Callback fn, boolean admin) {}

    private final Supplier<MinecraftServer> server;
    private final Map<String, Command> commands = new HashMap<>();
    /** Command nodes we added; they survive their resource (see {@link #register}). */
    private final Set<String> ownNodes = new java.util.HashSet<>();
    private Store store;
    private final Module players, chat;

    ServerApi(ModuleRegistry modules, ResourceHost host, Supplier<MinecraftServer> server) {
        this.server = server;

        players = modules.global("players")
                .function("list", a -> server().getPlayerList().getPlayers().stream().map(ServerApi::peer).toList())
                .function("get", a -> {
                    String key = a.string(0);
                    ServerPlayer p = find(key);
                    return p == null ? null : peer(p);
                })
                .function("online", a -> find(a.string(0)) != null)
                .function("message", a -> {
                    player(a).sendSystemMessage(Component.literal(a.string(1)));
                    return null;
                })
                .function("pos", a -> {
                    ServerPlayer p = player(a);
                    Vec3 v = p.position();
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", v.x);
                    pos.put("y", v.y);
                    pos.put("z", v.z);
                    pos.put("dim", Compat.dimension(Compat.level(p)));
                    return pos;
                })
                .function("teleport", a -> {
                    ServerPlayer p = player(a);
                    ServerLevel level = a.get(4) == null ? Compat.level(p) : level(a.string(4));
                    return Compat.teleport(p, level, a.number(1), a.number(2), a.number(3));
                })
                .function("give", a -> {
                    var item = BuiltInRegistries.ITEM.getOptional(id(a.string(1)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown item '" + a.string(1) + "'"));
                    long count = a.get(2) == null ? 1 : a.integer(2);
                    if (count < 1 || count > 64 * 36) throw new IllegalArgumentException("count must be 1.." + 64 * 36);
                    return player(a).addItem(new ItemStack(item, (int) count));
                })
                .event("join").event("leave");

        // A resource that (re)starts with players online gets on_join for each of them, as if they had just joined:
        // its per-player state starts empty, and scripts set it up in one place.
        host.onStart(resource -> {
            MinecraftServer s = server.get();
            if (s == null) return;
            for (ServerPlayer p : s.getPlayerList().getPlayers()) modules.emitTo(resource, players, "join", peer(p));
        });

        chat = modules.global("chat")
                .function("broadcast", a -> {
                    server().getPlayerList().broadcastSystemMessage(Component.literal(a.string(0)), false);
                    return null;
                })
                .event("message");
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> chat.emit("message", peer(sender), message.signedContent()));

        modules.global("command").function("register", this::register);
        host.onStop(resource -> commands.values().removeIf(c -> c.resource().equals(resource)));

        modules.global("world")
                // Only loaded chunks: a script must never make the server load or generate terrain.
                .function("block", a -> {
                    ServerLevel level = level(a, 3);
                    BlockPos pos = pos(a);
                    if (!level.isLoaded(pos)) return null;
                    return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
                })
                .function("set_block", a -> {
                    Block block = BuiltInRegistries.BLOCK.getOptional(id(a.string(3)))
                            .orElseThrow(() -> new IllegalArgumentException("unknown block '" + a.string(3) + "'"));
                    ServerLevel level = level(a, 4);
                    BlockPos pos = pos(a);
                    return level.isLoaded(pos) && level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
                });

        modules.global("store")
                .function("get", a -> store().get(a.resource(), a.string(0)))
                .function("set", a -> {
                    store().set(a.resource(), a.string(0), a.get(1));
                    return null;
                })
                .function("delete", a -> {
                    store().set(a.resource(), a.string(0), null);
                    return null;
                })
                .function("keys", a -> store().keys(a.resource()));
    }

    private final List<ServerPlayer> joining = new java.util.ArrayList<>();

    /**
     * A player finished joining (with or without Troupe on their side). Fabric's JOIN fires before the player is in
     * the player list, so on_join runs on the next tick, when {@code player:message} and friends work.
     */
    void joined(ServerPlayer p) {
        joining.add(p);
    }

    void left(ServerPlayer p) {
        players.emit("leave", peer(p));
    }

    /** End of every server tick: saves what changed in the stores. */
    void tick() {
        for (ServerPlayer p : List.copyOf(joining)) if (!p.hasDisconnected()) players.emit("join", peer(p));
        joining.clear();
        if (store != null) store.flush();
    }

    static Peer peer(ServerPlayer p) {
        return new Peer(p.getUUID().toString(), Compat.name(p.getGameProfile()));
    }

    private MinecraftServer server() {
        MinecraftServer s = server.get();
        if (s == null) throw new IllegalStateException("the server isn't running");
        return s;
    }

    private Store store() {
        if (store == null) store = new Store(Compat.serverDir(server()).resolve("troupe/store"));
        return store;
    }

    private ServerPlayer find(String idOrName) {
        try {
            return server().getPlayerList().getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notAnId) {
            return server().getPlayerList().getPlayerByName(idOrName);
        }
    }

    private ServerPlayer player(Args a) {
        ServerPlayer p = find(a.string(0));
        if (p == null) throw new IllegalArgumentException("player isn't online");
        return p;
    }

    private static Identifier id(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) throw new IllegalArgumentException("bad id '" + id + "'");
        return parsed;
    }

    private ServerLevel level(String dim) {
        ServerLevel level = server().getLevel(ResourceKey.create(Registries.DIMENSION, id(dim)));
        if (level == null) throw new IllegalArgumentException("no dimension '" + dim + "'");
        return level;
    }

    private ServerLevel level(Args a, int i) {
        return a.get(i) == null ? server().overworld() : level(a.string(i));
    }

    private static BlockPos pos(Args a) {
        return BlockPos.containing(a.number(0), a.number(1), a.number(2));
    }

    /**
     * {@code command.register(name, fn, opts)}: adds {@code /name} for everyone (or admins), at any time.
     * ponytail: Brigadier can't drop a node, so a stopped resource's command stays in the tree and answers that it's
     * gone until the server restarts.
     */
    private Object register(Args a) {
        String name = a.string(0);
        Callback fn = a.callback(1);
        boolean admin = a.get(2) != null && Boolean.TRUE.equals(a.table(2).get("admin"));
        if (!COMMAND.matcher(name).matches()) throw new IllegalArgumentException("bad command name '" + name + "'");
        Command existing = commands.get(name);
        if (existing != null && !existing.resource().equals(a.resource()))
            throw new IllegalArgumentException("/" + name + " belongs to " + existing.resource());
        MinecraftServer s = server();
        var dispatcher = s.getCommands().getDispatcher();
        boolean nodeExists = dispatcher.getRoot().getChild(name) != null;
        if (nodeExists && !ownNodes.contains(name)) throw new IllegalArgumentException("/" + name + " is already a command");
        commands.put(name, new Command(a.resource(), fn, admin));
        if (!nodeExists) {
            ownNodes.add(name);
            dispatcher.register(Commands.literal(name)
                    .executes(c -> run(c, name, ""))
                    .then(Commands.argument("args", StringArgumentType.greedyString()).executes(c -> run(c, name, StringArgumentType.getString(c, "args")))));
            for (ServerPlayer p : s.getPlayerList().getPlayers()) s.getCommands().sendCommands(p);
        }
        return null;
    }

    private int run(CommandContext<CommandSourceStack> c, String name, String args) {
        Command cmd = commands.get(name);
        CommandSourceStack src = c.getSource();
        if (cmd == null) {
            src.sendFailure(Component.literal("/" + name + " is gone (its resource stopped)"));
            return 0;
        }
        if (cmd.admin() && !Compat.permission(src, 2)) {
            src.sendFailure(Component.literal("You don't have permission to use /" + name));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        List<String> words = args.isBlank() ? List.of() : Arrays.asList(args.trim().split("\\s+"));
        cmd.fn().invoke(player == null ? null : peer(player), words);
        return 1;
    }
}

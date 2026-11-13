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
 *   player:mods() → the mod modules on their client (mods.* there)   player:has_mod(name)
 * chat.broadcast(text)  chat.on_message(fn(player, text))
 *   player:has_permission('banco.admin') → what a permissions mod (LuckPerms...) says; operators when none decides
 * command.register(name, fn(player|nil, args), { admin = false, permission = 'banco.sacar' })
 * world.block(x, y, z, dim?) → id | nil    world.set_block(x, y, z, id, dim?, { facing = "north" }?) → ok   (loaded chunks only)
 * store.get(key)  store.set(key, value)  store.delete(key)  store.keys()     (per resource, kept on disk)
 * </pre>
 * Server thread only, like everything a module function does.
 */
final class ServerApi {
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final Pattern NODE = Pattern.compile("[a-z0-9_.*-]{1,128}");

    private record Command(String resource, Callback fn, boolean admin, String permission) {}

    private static String node(String node) {
        if (!NODE.matcher(node).matches()) throw new IllegalArgumentException("bad permission '" + node + "'");
        return node;
    }

    private final Supplier<MinecraftServer> server;
    private final Map<String, Command> commands = new HashMap<>();
    /** Command nodes we added; they survive their resource (see {@link #register}). */
    private final Set<String> ownNodes = new java.util.HashSet<>();
    private Store store;
    private GameApi game;
    private final Module players, chat, zone, interact;
    private final ResourceHost host;

    /** An interact.add target: where it is (a fixed position, or an entity) and from how far it can be used. */
    private record Target(String resource, String dim, String entity, double[] pos, double distance) {}

    private final Map<Long, Target> targets = new HashMap<>();
    private long nextTarget;
    private final ModuleRegistry modules;
    private final dev.holoweb.troupe.lua.Zones zones = new dev.holoweb.troupe.lua.Zones();
    private int ticks;

    ServerApi(ModuleRegistry modules, ResourceHost host, Supplier<MinecraftServer> server) {
        this.server = server;
        this.modules = modules;
        this.host = host;

        players = modules.global("players")
                .function("list", a -> server().getPlayerList().getPlayers().stream().map(ServerApi::peer).toList())
                .function("get", a -> {
                    String key = a.string(0);
                    ServerPlayer p = find(key);
                    return p == null ? null : peer(p);
                })
                .function("online", a -> find(a.string(0)) != null)
                .function("mods", a -> host.network() instanceof dev.holoweb.troupe.lua.Links l ? l.clientModules(a.string(0)) : List.of())
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
                    pos.put("yaw", (double) p.getYRot());
                    pos.put("pitch", (double) p.getXRot());
                    return pos;
                })
                .function("teleport", a -> {
                    ServerPlayer p = player(a);
                    ServerLevel level = a.get(4) == null ? Compat.level(p) : level(a.string(4));
                    return Compat.teleport(p, level, a.number(1), a.number(2), a.number(3));
                })
                .function("has_permission", a -> me.lucko.fabric.api.permissions.v0.Permissions.check(player(a), node(a.string(1)), 2))
                .function("give", a -> player(a).addItem(game.stack(a.get(1), a.get(2), true)))
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

        // zone.box / zone.sphere and their objects are in the prelude.
        zone = modules.global("zone")
                .function("create", a -> {
                    Map<String, Object> spec = a.table(0);
                    ServerLevel level = spec.get("dim") == null ? server().overworld() : level(String.valueOf(spec.get("dim")));
                    return zones.create(a.resource(), Compat.dimension(level), spec);
                })
                .function("remove", a -> {
                    zones.remove(a.resource(), a.integer(0));
                    return null;
                })
                .function("players", a -> zones.inside(a.resource(), a.integer(0)).stream()
                        .map(this::find).filter(java.util.Objects::nonNull).map(ServerApi::peer).toList())
                .event("enter").event("leave");
        host.onStop(zones::removeAll);

        // interact.add(target, { label, distance }, fn) is in the prelude. Clients get the targets as global state.
        interact = modules.global("interact")
                .function("add", this::addTarget)
                .function("remove", a -> {
                    Target t = targets.get(a.integer(0));
                    if (t == null || !t.resource().equals(a.resource())) return null;
                    targets.remove(a.integer(0));
                    host.setState(a.resource(), null, ClientInteract.PREFIX + a.integer(0), null);
                    return null;
                })
                .event("use");
        host.onStop(resource -> targets.values().removeIf(t -> t.resource().equals(resource))); // the host drops the state
        host.onBuiltin((resource, from, name, json) -> {
            if (from == null || !name.equals(ClientInteract.EVENT)) return;
            try { // from a client: nothing in it is trusted
                long id = com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("id").getAsLong();
                Target t = targets.get(id);
                ServerPlayer p = find(from.id());
                if (t == null || p == null || !t.resource().equals(resource)) return;
                double[] at = t.pos();
                if (t.entity() != null) {
                    var e = Compat.level(p).getEntity(UUID.fromString(t.entity()));
                    if (e == null) return;
                    at = new double[]{e.getX(), e.getY(), e.getZ()};
                } else if (!t.dim().equals(Compat.dimension(Compat.level(p)))) {
                    return;
                }
                double reach = t.distance() + 3; // the client measured from the eyes, to the face it was looking at
                if (p.distanceToSqr(at[0], at[1], at[2]) > reach * reach) return;
                modules.emitTo(resource, interact, "use", id, peer(p));
            } catch (RuntimeException e) {
                // a malformed message is just ignored
            }
        });

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
                    var state = GameApi.state(block, a.get(5) == null ? Map.of() : a.table(5));
                    return level.isLoaded(pos) && level.setBlock(pos, state, Block.UPDATE_ALL);
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

        game = new GameApi(modules, this);
        host.onStop(game::forget);
        new GameEvents(modules);
        new ScoreApi(modules, host, this);
    }

    private final List<ServerPlayer> joining = new java.util.ArrayList<>();

    /**
     * A player finished joining (with or without Troupe on their side). Fabric's JOIN fires before the player is in
     * the player list, so on_join runs on the next tick, when {@code player:message} and friends work.
     */
    void joined(ServerPlayer p) {
        joining.add(p);
    }

    private Object addTarget(Args a) {
        Map<String, Object> spec = a.table(0), opts = a.get(1) == null ? Map.of() : a.table(1);
        String label = String.valueOf(opts.getOrDefault("label", ""));
        if (label.isBlank() || label.length() > 64) throw new IllegalArgumentException("label must have 1..64 characters");
        double distance = opts.get("distance") instanceof Number n ? n.doubleValue() : 4;
        if (!(distance >= 1 && distance <= 16)) throw new IllegalArgumentException("distance must be 1..16");
        ServerLevel level = opts.get("dim") == null ? server().overworld() : level(String.valueOf(opts.get("dim")));
        String dim = Compat.dimension(level);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("label", label);
        wire.put("distance", distance);
        Target target;
        if (spec.get("entity") != null) {
            Object e = spec.get("entity") instanceof Map<?, ?> m ? m.get("id") : spec.get("entity");
            String id = UUID.fromString(String.valueOf(e)).toString(); // throws on anything that isn't an entity id
            wire.put("kind", "entity");
            wire.put("entity", id);
            target = new Target(a.resource(), null, id, null, distance);
        } else {
            boolean block = spec.get("block") != null;
            double[] p = dev.holoweb.troupe.lua.Zones.point(block ? spec.get("block") : spec.get("pos"));
            if (block) p = new double[]{Math.floor(p[0]) + 0.5, Math.floor(p[1]) + 0.5, Math.floor(p[2]) + 0.5};
            double radius = spec.get("radius") instanceof Number n ? n.doubleValue() : 0.75;
            if (!(radius > 0 && radius <= 4)) throw new IllegalArgumentException("radius must be 0..4");
            wire.put("kind", block ? "block" : "pos");
            wire.put("x", p[0]);
            wire.put("y", p[1]);
            wire.put("z", p[2]);
            wire.put("radius", radius);
            wire.put("dim", dim);
            target = new Target(a.resource(), dim, null, p, distance);
        }
        if (targets.values().stream().filter(t -> t.resource().equals(a.resource())).count() >= 1024)
            throw new IllegalArgumentException("at most 1024 interact targets per resource");
        long id = ++nextTarget;
        targets.put(id, target);
        host.setState(a.resource(), null, ClientInteract.PREFIX + id, new com.google.gson.Gson().toJson(wire));
        return id;
    }

    /** The server stopped: the next one (another singleplayer world) has its own store folder. */
    void stopped() {
        store = null;
    }

    void left(ServerPlayer p) {
        Peer peer = peer(p);
        Instances.forget(p.getUUID());
        zones.left(peer.id(), (resource, enter, id) -> modules.emitTo(resource, zone, "leave", id, peer));
        players.emit("leave", peer(p));
    }

    /** End of every server tick: saves what changed in the stores. */
    void tick() {
        for (ServerPlayer p : List.copyOf(joining)) if (!p.hasDisconnected()) players.emit("join", peer(p));
        joining.clear();
        // Zones: four times a second is plenty for someone walking in or out.
        MinecraftServer s = server.get();
        if (++ticks % 5 == 0 && s != null && !zones.isEmpty()) for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Peer peer = peer(p);
            zones.update(peer.id(), Compat.dimension(Compat.level(p)), p.getX(), p.getY(), p.getZ(),
                    (resource, enter, id) -> modules.emitTo(resource, zone, enter ? "enter" : "leave", id, peer));
        }
        if (store != null) store.flush();
    }

    static Peer peer(ServerPlayer p) {
        return new Peer(p.getUUID().toString(), Compat.name(p.getGameProfile()));
    }

    MinecraftServer serverOrNull() {
        return server.get();
    }

    MinecraftServer server() {
        MinecraftServer s = server.get();
        if (s == null) throw new IllegalStateException("the server isn't running");
        return s;
    }

    private Store store() {
        if (store == null) store = new Store(Compat.serverDir(server()).resolve("troupe/store"));
        return store;
    }

    ServerPlayer find(String idOrName) {
        try {
            return server().getPlayerList().getPlayer(UUID.fromString(idOrName));
        } catch (IllegalArgumentException notAnId) {
            return server().getPlayerList().getPlayerByName(idOrName);
        }
    }

    ServerPlayer player(Args a) {
        ServerPlayer p = find(a.string(0));
        if (p == null) throw new IllegalArgumentException("player isn't online");
        return p;
    }

    static Identifier id(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) throw new IllegalArgumentException("bad id '" + id + "'");
        return parsed;
    }

    ServerLevel level(String dim) {
        ServerLevel level = server().getLevel(ResourceKey.create(Registries.DIMENSION, id(dim)));
        if (level == null) throw new IllegalArgumentException("no dimension '" + dim + "'");
        return level;
    }

    ServerLevel level(Args a, int i) {
        return a.get(i) == null ? server().overworld() : level(a.string(i));
    }

    private static BlockPos pos(Args a) {
        return BlockPos.containing(a.number(0), a.number(1), a.number(2));
    }

    /**
     * {@code command.register(name, fn, opts)}: adds {@code /name} for everyone, for admins, or for who has
     * {@code opts.permission} (operators when no permissions mod decides), at any time.
     * ponytail: Brigadier can't drop a node, so a stopped resource's command stays in the tree and answers that it's
     * gone until the server restarts.
     */
    private Object register(Args a) {
        String name = a.string(0);
        Callback fn = a.callback(1);
        boolean admin = a.get(2) != null && Boolean.TRUE.equals(a.table(2).get("admin"));
        String permission = a.get(2) != null && a.table(2).get("permission") != null ? node(a.table(2).get("permission").toString()) : null;
        if (!COMMAND.matcher(name).matches()) throw new IllegalArgumentException("bad command name '" + name + "'");
        Command existing = commands.get(name);
        if (existing != null && !existing.resource().equals(a.resource()))
            throw new IllegalArgumentException("/" + name + " belongs to " + existing.resource());
        MinecraftServer s = server();
        var dispatcher = s.getCommands().getDispatcher();
        boolean nodeExists = dispatcher.getRoot().getChild(name) != null;
        if (nodeExists && !ownNodes.contains(name)) throw new IllegalArgumentException("/" + name + " is already a command");
        commands.put(name, new Command(a.resource(), fn, admin, permission));
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
        if (cmd.admin() && !Compat.permission(src, 2)
                || cmd.permission() != null && !me.lucko.fabric.api.permissions.v0.Permissions.check(src, cmd.permission(), 2)) {
            src.sendFailure(Component.literal("You don't have permission to use /" + name));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        List<String> words = args.isBlank() ? List.of() : Arrays.asList(args.trim().split("\\s+"));
        cmd.fn().invoke(player == null ? null : peer(player), words);
        return 1;
    }
}

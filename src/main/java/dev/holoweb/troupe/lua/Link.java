package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.net.ClientSync;
import dev.holoweb.troupe.net.Protocol;
import dev.holoweb.troupe.net.ProtocolException;
import dev.holoweb.troupe.net.ServerResources;
import dev.holoweb.troupe.net.ServerSync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One end of a play-phase connection: encodes what the host sends, decodes what arrives and hands it to the host.
 * On the server there is one per player ({@code peer} set); on the client one for the server ({@code peer} null).
 * When the server's running resources change, it {@link #push}es the new manifest and the client downloads what it
 * lacks with the same messages as during configuration. Game thread only.
 */
public final class Link implements Network {
    /** Messages a client may send per second on average, and in one burst. */
    static final double RATE = 20, BURST = 40;
    private static final String DISCONNECTED = Json.encode("disconnected");

    /** Sends one message; {@code onSent} (may be null) runs once it's written, on any thread. */
    public interface Out {
        void send(byte[] message, Runnable onSent);

        void disconnect(String reason);
    }

    private final ResourceHost host;
    private final Peer peer;
    private final Out out;
    private final Runnable onStart;
    private final Path cache;
    private final BiConsumer<Protocol.Manifest, Runnable> onManifest;
    private ServerSync serverSync;
    private ServerResources nextPush;
    /** Server: the client's mod modules, once its HELLO came; what waits for it until then. */
    private List<String> clientModules;
    private final List<Runnable> afterHello = new ArrayList<>();
    private Runnable afterPush;
    private ClientSync clientSync;
    private final Map<Integer, Reply> pending = new HashMap<>();
    private int nextId;
    private double tokens = BURST;
    private long lastRefill;
    private boolean warned;
    private boolean closed;

    /** Server side: one per player. */
    public Link(ResourceHost host, Peer peer, Out out) {
        this(host, peer, out, () -> {}, null, (m, ready) -> {});
    }

    /**
     * Client side: {@code onStart} runs when the server says its state is here and resources may start;
     * {@code onManifest} when a pushed manifest is fully in {@code cache}, with the {@code Runnable} that tells the
     * server so (call it once the client is done with the files, e.g. assets loaded).
     */
    public Link(ResourceHost host, Out out, Runnable onStart, Path cache, BiConsumer<Protocol.Manifest, Runnable> onManifest) {
        this(host, null, out, onStart, cache, onManifest);
    }

    private Link(ResourceHost host, Peer peer, Out out, Runnable onStart, Path cache, BiConsumer<Protocol.Manifest, Runnable> onManifest) {
        this.host = host;
        this.peer = peer;
        this.out = out;
        this.onStart = onStart;
        this.cache = cache;
        this.onManifest = onManifest;
        lastRefill = host.now();
    }

    /** Server: the mod modules the client has ({@code mods.*} there); empty until its HELLO. */
    public List<String> clientModules() {
        return clientModules == null ? List.of() : clientModules;
    }

    /** Server: runs {@code r} once the client said HELLO (now, if it already did). */
    public void whenHello(Runnable r) {
        if (clientModules != null) r.run();
        else afterHello.add(r);
    }

    public Peer peer() {
        return peer;
    }

    @Override
    public void emit(String resource, String player, String name, String json) {
        send(new Protocol.Out(Protocol.EVENT).string(resource).string(name).string(json));
    }

    @Override
    public void call(String resource, String player, String name, String json, Reply reply) {
        if (closed) {
            reply.reply(false, DISCONNECTED);
            return;
        }
        int id = nextId++;
        pending.put(id, reply);
        send(new Protocol.Out(Protocol.CALL).varint(id).string(resource).string(name).string(json));
    }

    @Override
    public void state(String resource, String player, String key, String json) {
        send(new Protocol.Out(Protocol.STATE).string(resource).bool(player == null).string(key).string(json == null ? "" : json));
    }

    @Override
    public void clear(String resource) {
        send(new Protocol.Out(Protocol.CLEAR).string(resource));
    }

    /** Server → client: everything the client needs before its resources start has been sent. */
    void start() {
        send(new Protocol.Out(Protocol.START));
    }

    /** Client: the download in progress, if any (for progress screens). */
    public ClientSync download() {
        return clientSync;
    }

    /** Server: the client should run {@code pack} now. One download at a time; a newer push waits for it. */
    public void push(ServerResources pack) {
        push(pack, null);
    }

    /** {@code then}: runs once the client has every file of {@code pack} (not if it disconnects first). */
    public void push(ServerResources pack, Runnable then) {
        if (closed) return;
        if (then != null) afterPush = then;
        if (serverSync != null) {
            nextPush = pack;
            return;
        }
        serverSync = new ServerSync(pack, new ServerSync.Sender() {
            public void send(byte[] message, Runnable onSent) {
                if (!closed) out.send(message, onSent);
            }

            public void disconnect(String reason) {
                out.disconnect(reason);
            }
        });
        serverSync.start();
    }

    private void send(Protocol.Out message) {
        if (!closed) out.send(message.toByteArray(), null);
    }

    /** A message from the other end. Server side: rate-limited, and everything in it is untrusted. */
    public void receive(byte[] message) throws IOException {
        if (closed) return;
        boolean fromClient = peer != null;
        int max = fromClient ? Protocol.MAX_JSON_TO_SERVER : Protocol.MAX_JSON_TO_CLIENT;
        Protocol.In in = new Protocol.In(message);
        int type = in.type();
        switch (type) {
            // ids 0 and 1: a pushed download (MANIFEST/CHUNK to the client, WANT/READY to the server)
            case Protocol.MANIFEST, Protocol.CHUNK -> {
                if (fromClient) {
                    if (serverSync == null) throw new ProtocolException("no download in progress");
                    if (serverSync.receive(message)) {
                        serverSync.close();
                        serverSync = null;
                        ServerResources next = nextPush;
                        nextPush = null;
                        if (next != null) push(next);
                        else if (afterPush != null) {
                            Runnable then = afterPush;
                            afterPush = null;
                            then.run();
                        }
                    }
                } else {
                    if (clientSync == null) {
                        if (type != Protocol.MANIFEST) throw new ProtocolException("CHUNK before MANIFEST");
                        clientSync = new ClientSync(cache, bytes -> out.send(bytes, null));
                    }
                    Protocol.Manifest done = clientSync.receive(message);
                    if (done != null) {
                        ClientSync sync = clientSync;
                        clientSync = null;
                        onManifest.accept(done, sync::ready);
                    }
                }
            }
            case Protocol.EVENT -> {
                String resource = in.string(), name = in.string(), json = in.text(max);
                in.end();
                if (allowed()) host.receiveEvent(resource, peer, name, json);
            }
            case Protocol.CALL -> {
                int id = in.varint();
                String resource = in.string(), name = in.string(), json = in.text(max);
                in.end();
                Reply reply = (ok, result) -> send(new Protocol.Out(Protocol.REPLY).varint(id).bool(ok).string(result));
                if (allowed()) host.receiveCall(resource, peer, name, json, reply);
                else reply.reply(false, Json.encode("rate limited"));
            }
            case Protocol.REPLY -> {
                int id = in.varint();
                boolean ok = in.bool();
                String json = in.text(max);
                in.end();
                Reply reply = pending.remove(id);
                if (reply != null) reply.reply(ok, json);
            }
            case Protocol.START -> {
                if (fromClient) throw new ProtocolException("clients can't send START");
                in.end();
                Protocol.Out hello = new Protocol.Out(Protocol.HELLO);
                List<String> mods = host.modules().modNames();
                hello.varint(mods.size());
                for (String m : mods) hello.string(m);
                send(hello);
                onStart.run();
            }
            case Protocol.HELLO -> {
                if (!fromClient) throw new ProtocolException("servers don't send HELLO");
                if (clientModules != null) throw new ProtocolException("HELLO sent twice");
                int count = in.count(Protocol.MAX_MODULES);
                List<String> mods = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String m = in.string();
                    if (!Protocol.validName(m)) throw new ProtocolException("bad module name in HELLO");
                    mods.add(m);
                }
                in.end();
                clientModules = List.copyOf(mods);
                List<Runnable> waiting = List.copyOf(afterHello);
                afterHello.clear();
                waiting.forEach(Runnable::run);
            }
            case Protocol.STATE, Protocol.CLEAR -> {
                if (fromClient) throw new ProtocolException("clients can't send state");
                String resource = in.string();
                if (type == Protocol.CLEAR) {
                    in.end();
                    host.receiveClear(resource);
                } else {
                    boolean global = in.bool();
                    String key = in.string(), json = in.text(max);
                    in.end();
                    host.receiveState(resource, global, key, json.isEmpty() ? null : json);
                }
            }
            default -> throw new ProtocolException("unknown play message " + type);
        }
    }

    /** Token bucket; only clients are limited (the server is trusted by its clients). */
    private boolean allowed() {
        if (peer == null) return true;
        long now = host.now();
        tokens = Math.min(BURST, tokens + (now - lastRefill) / 1e9 * RATE);
        lastRefill = now;
        if (tokens >= 1) {
            tokens--;
            warned = false;
            return true;
        }
        if (!warned) LuaRuntime.LOG.warn("{} sends too many Troupe messages; dropping", peer.name());
        warned = true;
        return false;
    }

    /** The connection is gone: pending calls fail, downloads stop. */
    public void close() {
        if (closed) return;
        closed = true;
        if (serverSync != null) serverSync.close();
        if (clientSync != null) clientSync.close();
        for (Reply r : pending.values()) r.reply(false, DISCONNECTED);
        pending.clear();
    }
}

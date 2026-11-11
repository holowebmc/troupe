package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.net.Protocol;
import dev.holoweb.troupe.net.ProtocolException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One end of a play-phase connection: encodes what the host sends, decodes what arrives and hands it to the host.
 * On the server there is one per player ({@code peer} set); on the client one for the server ({@code peer} null).
 * Game thread only.
 */
public final class Link implements Network {
    /** Messages a client may send per second on average, and in one burst. */
    static final double RATE = 20, BURST = 40;
    private static final String DISCONNECTED = Json.encode("disconnected");

    private final ResourceHost host;
    private final Peer peer;
    private final Consumer<byte[]> out;
    private final Runnable onStart;
    private final Map<Integer, Reply> pending = new HashMap<>();
    private int nextId;
    private double tokens = BURST;
    private long lastRefill;
    private boolean warned;
    private boolean closed;

    /** Server side: one per player. */
    public Link(ResourceHost host, Peer peer, Consumer<byte[]> out) {
        this(host, peer, out, () -> {});
    }

    /** Client side: {@code onStart} runs when the server says its state is here and resources may start. */
    public Link(ResourceHost host, Consumer<byte[]> out, Runnable onStart) {
        this(host, null, out, onStart);
    }

    private Link(ResourceHost host, Peer peer, Consumer<byte[]> out, Runnable onStart) {
        this.host = host;
        this.peer = peer;
        this.out = out;
        this.onStart = onStart;
        lastRefill = host.now();
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

    private void send(Protocol.Out message) {
        if (!closed) out.accept(message.toByteArray());
    }

    /** A message from the other end. Server side: rate-limited, and everything in it is untrusted. */
    public void receive(byte[] message) throws ProtocolException {
        if (closed) return;
        boolean fromClient = peer != null;
        int max = fromClient ? Protocol.MAX_JSON_TO_SERVER : Protocol.MAX_JSON_TO_CLIENT;
        Protocol.In in = new Protocol.In(message);
        int type = in.type();
        switch (type) {
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
                onStart.run();
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

    /** The connection is gone: pending calls fail. */
    public void close() {
        if (closed) return;
        closed = true;
        for (Reply r : pending.values()) r.reply(false, DISCONNECTED);
        pending.clear();
    }
}

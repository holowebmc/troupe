package dev.holoweb.troupe;

import com.google.common.collect.MapMaker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.holoweb.troupe.net.Protocol;
import dev.holoweb.troupe.net.Protocol.Manifest;
import net.minecraft.network.protocol.status.ServerStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Troupe in the server list ping: the status JSON gets a {@code "troupe"} object (protocol, resource count, download
 * size, names), which vanilla clients ignore. {@code ServerStatusMixin} wraps the status codec with {@link #wrap}.
 */
public final class TroupeStatus {
    /** What a server says about its resources; {@code size} is the whole client download, in bytes. */
    public record Info(int protocol, int resources, long size, List<String> names) {
        public boolean compatible() {
            return protocol == Protocol.VERSION;
        }
    }

    private static final int MAX_NAMES = 20;
    private static volatile Supplier<Manifest> published = () -> Manifest.EMPTY;
    /** Statuses decoded with a troupe object, until the pinger hands it to its ServerData (weak, by identity). */
    private static final Map<ServerStatus, Info> received = new MapMaker().weakKeys().makeMap();

    private TroupeStatus() {}

    /** Server: what the ping shows (the running resources clients would download). */
    static void serve(Supplier<Manifest> manifest) {
        published = manifest;
    }

    /** The troupe object a status sent by this side gets, or null when there's nothing to show. */
    private static JsonObject json() {
        Manifest m = published.get();
        if (m.resources().isEmpty()) return null;
        JsonObject o = new JsonObject();
        o.addProperty("protocol", Protocol.VERSION);
        o.addProperty("resources", m.resources().size());
        o.addProperty("size", m.blobs().stream().mapToLong(Protocol.Blob::size).sum());
        JsonArray names = new JsonArray();
        m.resources().keySet().stream().sorted().limit(MAX_NAMES).forEach(names::add);
        o.add("names", names);
        return o;
    }

    /** Untrusted (any server): null unless it looks right; names are checked like resource names. */
    static Info parse(JsonElement e) {
        try {
            JsonObject o = e.getAsJsonObject();
            int protocol = o.get("protocol").getAsInt(), resources = o.get("resources").getAsInt();
            long size = o.get("size").getAsLong();
            if (resources < 0 || size < 0) return null;
            List<String> names = new ArrayList<>();
            if (o.has("names")) for (JsonElement n : o.getAsJsonArray("names")) {
                if (names.size() == MAX_NAMES) break;
                if (n.isJsonPrimitive() && Protocol.validName(n.getAsString())) names.add(n.getAsString());
            }
            return new Info(protocol, resources, size, List.copyOf(names));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The troupe info that came with {@code status} on this client, if any. */
    public static Info received(ServerStatus status) {
        return status == null ? null : received.get(status);
    }

    public static Codec<ServerStatus> wrap(Codec<ServerStatus> codec) {
        return new Codec<>() {
            @Override
            public <T> DataResult<T> encode(ServerStatus input, DynamicOps<T> ops, T prefix) {
                DataResult<T> out = codec.encode(input, ops, prefix);
                JsonObject troupe = json();
                if (troupe == null) return out;
                return out.flatMap(t -> ops.mergeToMap(t, ops.createString("troupe"), JsonOps.INSTANCE.convertTo(ops, troupe)));
            }

            @Override
            public <T> DataResult<Pair<ServerStatus, T>> decode(DynamicOps<T> ops, T input) {
                DataResult<Pair<ServerStatus, T>> out = codec.decode(ops, input);
                out.result().ifPresent(p -> ops.get(input, "troupe").result().ifPresent(t -> {
                    Info info = TroupeStatus.parse(ops.convertTo(JsonOps.INSTANCE, t));
                    if (info != null) received.put(p.getFirst(), info);
                }));
                return out;
            }

            @Override
            public String toString() {
                return "Troupe[" + codec + "]";
            }
        };
    }
}

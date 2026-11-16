package dev.holoweb.troupe.net;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Wire format of the {@code troupe:v1} channel: plain bytes inside a custom payload, no Minecraft types, so any
 * server platform can speak it. Every message starts with a type byte; ids are per direction.
 *
 * <pre>
 * server → client
 *   MANIFEST  varint version, varint oldest version the server still speaks (these two never change place),
 *             32 bytes cache key (see {@link BlobCache}), varint blobs, blobs × (32 bytes sha256, varlong size),
 *             varint resources, resources × (string name, varint generation, varint files, files × (string path, varint blob)),
 *             string download ("" or an http(s) URL ending in '/': the client may GET url + sha256 instead of asking for CHUNKs)
 *   CHUNK     varint blob, data up to the end of the message (blobs go in WANT order, each from offset 0)
 *   (the same four messages are reused in the play phase when the server's running resources change)
 * client → server
 *   WANT      varint version (the client's newest; both sides then speak the lower of the two), bitmap of the blobs the client lacks (bit i = blob i, LSB first)
 *   READY     every wanted blob is in the client cache
 *
 * play phase, both directions (json: arguments or result, UTF-8 JSON text)
 *   EVENT     string resource, string name, text json
 *   CALL      varint id, string resource, string name, text json
 *   REPLY     varint id, u8 ok, text json (the result, or the error message as a JSON string)
 * play phase, client → server only
 *   HELLO     varint count, (string name, string version)s: the client's mod modules (answering START; the server's players.on_join waits for it)
 * play phase, server → client only
 *   STATE     string resource, u8 global, string key, text json ("" removes the key)
 *   CLEAR     string resource (it stopped on the server: drop its state)
 *   ENTITY    string resource, string entity, string key, text json: state of an entity the player can see
 *             ("" json removes the key; "" key forgets the entity, which went out of sight)
 *   START     the global state has been sent: start the resources now
 * </pre>
 */
public final class Protocol {
    public static final String CHANNEL = "troupe:v1";
    /**
     * The newest protocol this build speaks, and the oldest it still accepts from the other side. Two builds can talk
     * when each one's newest is at least the other's oldest; they then speak the lower of the two newest. A version
     * that only adds (fields at the end of MANIFEST, new message types) keeps MIN_VERSION; one that changes what
     * exists raises it.
     */
    public static final int VERSION = 4, MIN_VERSION = 4;

    /** Can this build talk to one that speaks {@code version} and accepts down to {@code min}? */
    public static boolean compatible(int version, int min) {
        return version >= MIN_VERSION && min <= VERSION;
    }

    public static final int MANIFEST = 0, CHUNK = 1; // server → client
    public static final int WANT = 0, READY = 1;     // client → server
    public static final int EVENT = 2, CALL = 3, REPLY = 4; // play phase, both ways
    public static final int STATE = 5, CLEAR = 6, START = 7; // play phase, server → client
    public static final int HELLO = 8; // play phase, client → server
    /** Never on the wire: {@link ClientSync} tells itself, on its caller's thread, that the HTTP downloads ended. */
    public static final int FETCHED = 9;
    public static final int ENTITY = 10; // play phase, server → client
    /** Most mod modules a HELLO may list. */
    public static final int MAX_MODULES = 256;

    /** JSON budget of one message: serverbound custom payloads are capped at 32 KiB, clientbound at 1 MiB. */
    public static final int MAX_JSON_TO_SERVER = 30 * 1024, MAX_JSON_TO_CLIENT = 1000 * 1024;

    /** Well below the 1 MiB clientbound custom payload limit. */
    public static final int CHUNK_SIZE = 256 * 1024;
    /** The WANT bitmap must fit the 32 KiB serverbound payload limit. */
    public static final int MAX_BLOBS = 200_000;
    public static final long MAX_TOTAL_SIZE = 1L << 30;

    /** Folders the client receives; everything else in a resource (server/) never leaves the server. */
    public static final List<String> CLIENT_DIRS = List.of("client", "shared", "ui", "assets", "models");
    /** Sent with the client files: the client needs the dependencies too. */
    public static final String RESOURCE_JSON = "resource.json";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern BAD_SEGMENT = Pattern.compile("\\.{1,2}|.*[\\\\:*?\"<>|\\p{Cntrl}].*");
    private static final HexFormat HEX = HexFormat.of();

    private Protocol() {}

    public record Blob(String sha256, long size) {}

    /**
     * {@code resources}: resource name → (path inside the resource → index in {@code blobs}).
     * {@code generations}: bumps every time the resource starts on the server; a client restarts its side when it changes.
     * {@code key}: the server's cache key (64 hex chars), "" until the server sets it.
     * {@code download}: "" or the URL blobs can be fetched from ({@code download + sha256}).
     * {@code modules}: the client's mod modules these resources may reach (their {@code mods.client}); the player
     * sees them before joining, and nothing else of {@code mods.*} answers on the client.
     */
    public record Manifest(List<Blob> blobs, Map<String, Map<String, Integer>> resources, Map<String, Integer> generations, String key, String download, List<String> modules) {
        public static final Manifest EMPTY = new Manifest(List.of(), Map.of(), Map.of());

        public Manifest(List<Blob> blobs, Map<String, Map<String, Integer>> resources, Map<String, Integer> generations) {
            this(blobs, resources, generations, "", "", List.of());
        }

        public Manifest withKey(String key) {
            return new Manifest(blobs, resources, generations, key, download, modules);
        }

        public Manifest withDownload(String url) {
            return new Manifest(blobs, resources, generations, key, url, modules);
        }

        public int generation(String resource) {
            return generations.getOrDefault(resource, 0);
        }
    }

    /** "" or an http(s) URL ending in '/'. The client fetches whatever it is told, like a server resource pack's URL. */
    public static boolean validDownload(String url) {
        return url.isEmpty() || url.matches("https?://[\\x21-\\x7e]{1,500}/");
    }

    /** Resource names end up as URL hosts ({@code vitrine://name/}), so they stay lowercase and simple. */
    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /**
     * {@code resource.json}, or relative, '/'-separated, inside one of {@link #CLIENT_DIRS}, no '..' and nothing
     * Windows can't name.
     */
    public static boolean validPath(String path) {
        if (path.equals(RESOURCE_JSON)) return true;
        if (path.length() > 256 || path.startsWith("/") || path.endsWith("/")) return false;
        String[] segments = path.split("/", -1);
        if (segments.length < 2 || !CLIENT_DIRS.contains(segments[0])) return false;
        for (String s : segments) if (s.isEmpty() || BAD_SEGMENT.matcher(s).matches()) return false;
        return true;
    }

    public static byte[] encodeManifest(Manifest m) {
        if (m.key().length() != 64) throw new IllegalStateException("the manifest has no cache key");
        Out out = new Out(MANIFEST).varint(VERSION).varint(MIN_VERSION).bytes(HEX.parseHex(m.key())).varint(m.blobs().size());
        for (Blob b : m.blobs()) out.bytes(HEX.parseHex(b.sha256())).varlong(b.size());
        out.varint(m.resources().size());
        m.resources().forEach((name, files) -> {
            out.string(name).varint(m.generation(name)).varint(files.size());
            files.forEach((path, blob) -> out.string(path).varint(blob));
        });
        out.string(m.download()).varint(m.modules().size());
        for (String module : m.modules()) out.string(module);
        return out.toByteArray();
    }

    /** Decodes and validates a manifest from the server (untrusted on the client). In is past the type byte. */
    public static Manifest decodeManifest(In in) throws ProtocolException {
        int version = in.varint(), min = in.varint();
        if (min > VERSION) throw new ProtocolException("this server needs a newer Troupe: update the mod (server protocol " + min + " or later, this client " + VERSION + ")");
        if (version < MIN_VERSION) throw new ProtocolException("this server runs a Troupe too old for this client (server protocol " + version + ", this client " + MIN_VERSION + " or later)");
        String key = HEX.formatHex(in.bytes(32));
        int blobCount = in.count(MAX_BLOBS);
        List<Blob> blobs = new ArrayList<>(blobCount);
        long total = 0;
        for (int i = 0; i < blobCount; i++) {
            String sha = HEX.formatHex(in.bytes(32));
            long size = in.varlong();
            if (size < 0 || (total += size) > MAX_TOTAL_SIZE) throw new ProtocolException("resources exceed " + (MAX_TOTAL_SIZE >> 20) + " MiB");
            blobs.add(new Blob(sha, size));
        }
        int resourceCount = in.count(10_000);
        Map<String, Map<String, Integer>> resources = new LinkedHashMap<>();
        Map<String, Integer> generations = new LinkedHashMap<>();
        for (int r = 0; r < resourceCount; r++) {
            String name = in.string();
            if (!validName(name) || resources.containsKey(name)) throw new ProtocolException("bad resource name: " + name);
            generations.put(name, in.varint());
            int fileCount = in.count(MAX_BLOBS);
            Map<String, Integer> files = new TreeMap<>();
            for (int f = 0; f < fileCount; f++) {
                String path = in.string();
                int blob = in.varint();
                if (!validPath(path) || files.containsKey(path)) throw new ProtocolException("bad path: " + name + "/" + path);
                if (blob < 0 || blob >= blobCount) throw new ProtocolException("bad blob index for " + name + "/" + path);
                files.put(path, blob);
            }
            resources.put(name, files);
        }
        String download = in.string();
        if (!validDownload(download)) throw new ProtocolException("bad download URL");
        int moduleCount = in.count(MAX_MODULES);
        List<String> modules = new ArrayList<>(moduleCount);
        for (int i = 0; i < moduleCount; i++) {
            String module = in.string();
            if (!validName(module)) throw new ProtocolException("bad module name: " + module);
            modules.add(module);
        }
        if (version <= VERSION) in.end(); // a newer server may have appended fields this build doesn't know
        return new Manifest(blobs, resources, generations, key, download, List.copyOf(modules));
    }

    /** Message writer: type byte first, then fields. */
    public static final class Out {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        public Out(int type) {
            buf.write(type);
        }

        public Out varint(int v) {
            return varlong(v & 0xFFFFFFFFL);
        }

        public Out varlong(long v) {
            while ((v & ~0x7FL) != 0) {
                buf.write((int) (v & 0x7F) | 0x80);
                v >>>= 7;
            }
            buf.write((int) v);
            return this;
        }

        public Out bytes(byte[] b) {
            buf.writeBytes(b);
            return this;
        }

        public Out bytes(byte[] b, int off, int len) {
            buf.write(b, off, len);
            return this;
        }

        public Out string(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            return varint(b.length).bytes(b);
        }

        public Out bool(boolean b) {
            buf.write(b ? 1 : 0);
            return this;
        }

        public byte[] toByteArray() {
            return buf.toByteArray();
        }
    }

    /** Message reader. Every malformed input ends in ProtocolException, never another exception. */
    public static final class In {
        private final ByteBuffer buf;

        public In(byte[] msg) {
            buf = ByteBuffer.wrap(msg);
        }

        public int type() throws ProtocolException {
            return u8();
        }

        public int u8() throws ProtocolException {
            try {
                return buf.get() & 0xFF;
            } catch (BufferUnderflowException e) {
                throw new ProtocolException("message too short");
            }
        }

        public int varint() throws ProtocolException {
            long v = varlong();
            if (v > Integer.MAX_VALUE) throw new ProtocolException("varint out of range");
            return (int) v;
        }

        public long varlong() throws ProtocolException {
            long v = 0;
            for (int shift = 0; shift < 63; shift += 7) {
                int b = u8();
                v |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return v;
            }
            throw new ProtocolException("varint too long");
        }

        public int count(int max) throws ProtocolException {
            int n = varint();
            if (n > max) throw new ProtocolException("too many entries: " + n);
            return n;
        }

        public byte[] bytes(int n) throws ProtocolException {
            if (n < 0 || n > buf.remaining()) throw new ProtocolException("message too short");
            byte[] b = new byte[n];
            buf.get(b);
            return b;
        }

        public byte[] rest() {
            byte[] b = new byte[buf.remaining()];
            buf.get(b);
            return b;
        }

        public String string() throws ProtocolException {
            return text(1024);
        }

        public String text(int maxBytes) throws ProtocolException {
            int n = varint();
            if (n > maxBytes) throw new ProtocolException("string too long");
            return new String(bytes(n), StandardCharsets.UTF_8);
        }

        public boolean bool() throws ProtocolException {
            return u8() != 0;
        }

        public void end() throws ProtocolException {
            if (buf.hasRemaining()) throw new ProtocolException(buf.remaining() + " trailing bytes");
        }
    }
}

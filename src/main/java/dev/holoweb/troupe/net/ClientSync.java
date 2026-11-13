package dev.holoweb.troupe.net;

import dev.holoweb.troupe.net.Protocol.Blob;
import dev.holoweb.troupe.net.Protocol.Manifest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Client side of one download into the server's {@link BlobCache} (encrypted, named by a keyed hash of the content):
 * file names never come from the server, so its paths can't reach the disk.
 */
public final class ClientSync {
    private final Path dir;
    private final Consumer<byte[]> out;
    private final Consumer<byte[]> self;
    private BlobCache cache;
    private Manifest manifest;
    private int[] missing;   // what wasn't in the cache when the manifest came (for progress)
    private int[] wanted;    // what the server must still send as CHUNKs; null until WANT goes out
    private int next;
    private volatile int fetchedFiles;
    private volatile boolean fetched, closed;
    private long received;   // bytes of the blob being written
    private OutputStream part;
    private Blob partBlob;
    private MessageDigest digest;
    private volatile long downloadedBytes;

    /** No HTTP: every missing file comes through {@code out}'s connection. */
    public ClientSync(Path dir, Consumer<byte[]> out) {
        this(dir, out, null);
    }

    /**
     * {@code self}: must hand the message to {@link #receive} on the thread that feeds this sync. With it, a manifest
     * that has a download URL is fetched over HTTP first (one background thread); what fails there is then asked
     * for as CHUNKs, so a dead URL only costs time.
     */
    public ClientSync(Path dir, Consumer<byte[]> out, Consumer<byte[]> self) {
        this.dir = dir;
        this.out = out;
        this.self = self;
    }

    /** @return the manifest once every file is in the cache, else null; then call {@link #ready()} */
    public Manifest receive(byte[] message) throws IOException {
        Protocol.In in = new Protocol.In(message);
        int type = in.type();
        switch (type) {
            case Protocol.MANIFEST -> {
                if (manifest != null) throw new ProtocolException("MANIFEST sent twice");
                manifest = Protocol.decodeManifest(in);
                cache = new BlobCache(dir, manifest.key());
                for (Blob b : manifest.blobs())
                    if (b.size() == 0 && !cache.has(b)) cache.write(b, new byte[0]); // nothing to download
                // ponytail: trusts size alone for cached blobs (no decrypt on every join); BlobCache.read checks content.
                missing = IntStream.range(0, manifest.blobs().size()).filter(i -> !cache.has(manifest.blobs().get(i))).toArray();
                if (self == null || manifest.download().isEmpty() || missing.length == 0) return want();
                Thread t = new Thread(this::fetch, "Troupe download");
                t.setDaemon(true);
                t.start();
                return null;
            }
            case Protocol.FETCHED -> {
                if (!fetched) throw new ProtocolException("unexpected FETCHED");
                fetched = false;
                return want();
            }
            case Protocol.CHUNK -> {
                if (wanted == null || next >= wanted.length) throw new ProtocolException("unexpected CHUNK");
                int blob = in.varint();
                if (blob != wanted[next]) throw new ProtocolException("CHUNK for blob " + blob + ", expected " + wanted[next]);
                Blob b = manifest.blobs().get(blob);
                byte[] data = in.rest();
                if (received + data.length > b.size()) throw new ProtocolException("blob " + blob + " longer than announced");
                if (part == null) {
                    digest = BlobCache.sha256();
                    part = new DigestOutputStream(cache.start(b), digest);
                    partBlob = b;
                }
                part.write(data);
                received += data.length;
                downloadedBytes += data.length;
                if (received == b.size()) {
                    part.close();
                    part = null;
                    partBlob = null;
                    if (!HexFormat.of().formatHex(digest.digest()).equals(b.sha256())) {
                        cache.discard(b);
                        throw new ProtocolException("blob " + blob + " does not match its SHA-256");
                    }
                    cache.commit(b);
                    received = 0;
                    next++;
                    return finishIfDone();
                }
                return null;
            }
            default -> throw new ProtocolException("unknown message " + type);
        }
    }

    /** Asks the server for what is still missing. */
    private Manifest want() {
        wanted = IntStream.of(missing).filter(i -> !cache.has(manifest.blobs().get(i))).toArray();
        byte[] bitmap = new byte[(manifest.blobs().size() + 7) / 8];
        for (int i : wanted) bitmap[i >> 3] |= (byte) (1 << (i & 7));
        out.accept(new Protocol.Out(Protocol.WANT).varint(Protocol.VERSION).bytes(bitmap).toByteArray());
        return finishIfDone();
    }

    /** Background thread: the missing blobs over HTTP, in order, until one fails. */
    // ponytail: one connection, one blob at a time; parallel requests if a CDN makes it worth it
    private void fetch() {
        byte[] buf = new byte[64 << 10];
        try {
            for (int i : missing) {
                Blob b = manifest.blobs().get(i);
                long got = 0;
                try {
                    java.net.URLConnection c = java.net.URI.create(manifest.download() + b.sha256()).toURL().openConnection();
                    c.setConnectTimeout(10_000);
                    c.setReadTimeout(30_000);
                    MessageDigest sha = BlobCache.sha256();
                    try (java.io.InputStream in = c.getInputStream(); OutputStream o = new DigestOutputStream(cache.start(b), sha)) {
                        for (int n; (n = in.read(buf)) > 0; ) {
                            if (closed) throw new IOException("cancelled");
                            if ((got += n) > b.size()) throw new IOException("longer than announced");
                            o.write(buf, 0, n);
                            downloadedBytes += n;
                        }
                    }
                    if (got != b.size() || !HexFormat.of().formatHex(sha.digest()).equals(b.sha256())) throw new IOException("doesn't match its SHA-256");
                    cache.commit(b);
                    fetchedFiles++;
                } catch (IOException | RuntimeException e) {
                    cache.discard(b);
                    downloadedBytes -= got;
                    if (!closed) org.slf4j.LoggerFactory.getLogger("troupe").warn(
                            "Download from {} failed ({}); the rest comes through the game connection", manifest.download(), e.toString());
                    break;
                }
            }
        } finally {
            fetched = true;
            if (!closed) self.accept(new byte[]{Protocol.FETCHED});
        }
    }

    public int downloadedFiles() {
        return fetchedFiles + (wanted == null ? 0 : next);
    }

    /** How many of the downloaded files came over HTTP. */
    public int httpFiles() {
        return fetchedFiles;
    }

    public long downloadedBytes() {
        return downloadedBytes;
    }

    /** Files and bytes this download fetches (0 until MANIFEST): what wasn't in the cache already. */
    public int wantedFiles() {
        return missing == null ? 0 : missing.length;
    }

    public long wantedBytes() {
        if (missing == null) return 0;
        long n = 0;
        for (int i : missing) n += manifest.blobs().get(i).size();
        return n;
    }

    /**
     * Tells the server everything is in: it lets the player in (or sends the next push). The caller decides when,
     * e.g. once the assets are loaded too.
     */
    public void ready() {
        out.accept(new Protocol.Out(Protocol.READY).toByteArray());
    }

    /** Where this server's files are (null until MANIFEST). */
    public BlobCache cache() {
        return cache;
    }

    /** Drops a half-written blob, e.g. when the connection dies mid-download. */
    public void close() {
        closed = true; // the HTTP thread drops its own half-written blob
        if (part == null) return;
        try {
            part.close();
        } catch (IOException ignored) {
        }
        cache.discard(partBlob);
        part = null;
        partBlob = null;
    }

    private Manifest finishIfDone() {
        return wanted == null || next < wanted.length ? null : manifest;
    }
}

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
    private BlobCache cache;
    private Manifest manifest;
    private int[] wanted;
    private int next;
    private long received;   // bytes of the blob being written
    private OutputStream part;
    private Blob partBlob;
    private MessageDigest digest;
    private long downloadedBytes;

    public ClientSync(Path dir, Consumer<byte[]> out) {
        this.dir = dir;
        this.out = out;
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
                wanted = IntStream.range(0, manifest.blobs().size()).filter(i -> !cache.has(manifest.blobs().get(i))).toArray();
                byte[] bitmap = new byte[(manifest.blobs().size() + 7) / 8];
                for (int i : wanted) bitmap[i >> 3] |= (byte) (1 << (i & 7));
                out.accept(new Protocol.Out(Protocol.WANT).varint(Protocol.VERSION).bytes(bitmap).toByteArray());
                return finishIfDone();
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

    public int downloadedFiles() {
        return wanted == null ? 0 : next;
    }

    public long downloadedBytes() {
        return downloadedBytes;
    }

    /** Files and bytes this download fetches (0 until MANIFEST): what wasn't in the cache already. */
    public int wantedFiles() {
        return wanted == null ? 0 : wanted.length;
    }

    public long wantedBytes() {
        if (wanted == null) return 0;
        long n = 0;
        for (int i : wanted) n += manifest.blobs().get(i).size();
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
        return next < wanted.length ? null : manifest;
    }
}

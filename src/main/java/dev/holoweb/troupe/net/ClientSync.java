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
 * Client side of one download. The cache is content-addressed ({@code cache/<sha256>}), shared by every server:
 * file names never come from the server, so its paths can't reach the disk.
 */
public final class ClientSync {
    private final Path cache;
    private final Consumer<byte[]> out;
    private Manifest manifest;
    private int[] wanted;
    private int next;
    private long received;   // bytes of the blob being written
    private OutputStream part;
    private MessageDigest digest;
    private long downloadedBytes;

    public ClientSync(Path cache, Consumer<byte[]> out) {
        this.cache = cache;
        this.out = out;
    }

    /** @return the manifest once every file is in the cache, else null */
    public Manifest receive(byte[] message) throws IOException {
        Protocol.In in = new Protocol.In(message);
        int type = in.type();
        switch (type) {
            case Protocol.MANIFEST -> {
                if (manifest != null) throw new ProtocolException("MANIFEST sent twice");
                manifest = Protocol.decodeManifest(in);
                Files.createDirectories(cache);
                for (Blob b : manifest.blobs()) {
                    Path p = cache.resolve(b.sha256());
                    if (b.size() == 0 && !Files.exists(p)) Files.createFile(p); // nothing to download
                }
                wanted = IntStream.range(0, manifest.blobs().size()).filter(i -> !cached(manifest.blobs().get(i))).toArray();
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
                    digest = sha256();
                    part = new DigestOutputStream(Files.newOutputStream(partFile(b)), digest);
                }
                part.write(data);
                received += data.length;
                downloadedBytes += data.length;
                if (received == b.size()) {
                    part.close();
                    part = null;
                    if (!HexFormat.of().formatHex(digest.digest()).equals(b.sha256())) {
                        Files.deleteIfExists(partFile(b));
                        throw new ProtocolException("blob " + blob + " does not match its SHA-256");
                    }
                    Files.move(partFile(b), cache.resolve(b.sha256()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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

    public Path file(Blob b) {
        return cache.resolve(b.sha256());
    }

    /** Drops a half-written blob, e.g. when the connection dies mid-download. */
    public void close() {
        if (part == null) return;
        try {
            part.close();
            Files.deleteIfExists(partFile(manifest.blobs().get(wanted[next])));
        } catch (IOException ignored) {
        }
        part = null;
    }

    private Manifest finishIfDone() {
        if (next < wanted.length) return null;
        out.accept(new Protocol.Out(Protocol.READY).toByteArray());
        return manifest;
    }

    // ponytail: trusts size alone for cached blobs (no rehash on every join); verify on load if tampering matters.
    private boolean cached(Blob b) {
        try {
            return Files.size(cache.resolve(b.sha256())) == b.size();
        } catch (IOException e) {
            return false;
        }
    }

    private Path partFile(Blob b) {
        return cache.resolve(b.sha256() + ".part");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}

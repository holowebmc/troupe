package dev.holoweb.troupe.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.stream.IntStream;

/**
 * Server side of one client's download: MANIFEST out, WANT in, CHUNKs out, READY in.
 * Chunks go one at a time: the next is read when the previous one has left (onSent), so a big resource never
 * sits whole in the connection's queue.
 */
public final class ServerSync {
    public interface Sender {
        /** {@code onSent} (may be null) runs once the message is written, on any thread. */
        void send(byte[] message, Runnable onSent);

        void disconnect(String reason);
    }

    private final ServerResources resources;
    private final Sender out;
    private int[] wanted;
    private int next;        // index into wanted of the blob being sent
    private InputStream current;
    private boolean closed;

    public ServerSync(ServerResources resources, Sender out) {
        this.resources = resources;
        this.out = out;
    }

    public void start() {
        out.send(Protocol.encodeManifest(resources.manifest()), null);
    }

    /** @return true once the client has every file */
    public synchronized boolean receive(byte[] message) throws ProtocolException {
        Protocol.In in = new Protocol.In(message);
        int type = in.type();
        switch (type) {
            case Protocol.WANT -> {
                if (wanted != null) throw new ProtocolException("WANT sent twice");
                int version = in.varint();
                if (version != Protocol.VERSION) throw new ProtocolException("client speaks protocol " + version + ", this server " + Protocol.VERSION);
                int blobs = resources.files().size();
                byte[] bitmap = in.rest();
                if (bitmap.length != (blobs + 7) / 8) throw new ProtocolException("bad WANT size");
                wanted = IntStream.range(0, blobs)
                        .filter(i -> (bitmap[i >> 3] & (1 << (i & 7))) != 0)
                        .toArray();
                sendNext();
                return false;
            }
            case Protocol.READY -> {
                if (wanted == null || next < wanted.length) throw new ProtocolException("READY before all files were sent");
                return true;
            }
            default -> throw new ProtocolException("unknown message " + type);
        }
    }

    private synchronized void sendNext() {
        if (closed) return;
        try {
            while (next < wanted.length) {
                int blob = wanted[next];
                if (current == null) current = Files.newInputStream(resources.files().get(blob));
                byte[] data = current.readNBytes(Protocol.CHUNK_SIZE);
                if (data.length < Protocol.CHUNK_SIZE) finishBlob(); // end of file (an exact multiple ends on an empty read)
                if (data.length > 0) {
                    out.send(new Protocol.Out(Protocol.CHUNK).varint(blob).bytes(data).toByteArray(), this::sendNext);
                    return;
                }
            }
        } catch (IOException e) {
            close();
            out.disconnect("Troupe: could not read a resource file (" + e.getMessage() + ")");
        }
    }

    private void finishBlob() throws IOException {
        current.close();
        current = null;
        next++;
    }

    public synchronized void close() {
        closed = true;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
            current = null;
        }
    }
}

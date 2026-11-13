package dev.holoweb.troupe.net;

import dev.holoweb.troupe.net.Protocol.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Server and client sync wired back to back in memory, the way the two ends of a connection see each other. */
class SyncTest {
    @TempDir
    Path tmp;

    @Test
    void downloadsClientFilesOnceAndNeverServerCode() throws IOException {
        Path root = tmp.resolve("resources");
        byte[] big = new byte[Protocol.CHUNK_SIZE * 2 + 123]; // three chunks
        new Random(1).nextBytes(big);
        write(root.resolve("banco/resource.json"), "{ // comments allowed\n \"version\": 1 }");
        write(root.resolve("banco/server/main.lua"), "-- server only");
        write(root.resolve("banco/client/main.lua"), "print('oi')");
        write(root.resolve("banco/shared/config.lua"), "return {}");
        write(root.resolve("banco/ui/app.js"), big);
        write(root.resolve("banco/ui/empty.css"), "");
        write(root.resolve("hud/resource.json"), "{}");
        write(root.resolve("hud/client/main.lua"), "print('oi')");        // same bytes as banco's: one blob
        write(root.resolve("so-servidor/resource.json"), "{}");
        write(root.resolve("so-servidor/server/main.lua"), "-- nothing for clients");
        write(root.resolve("Nome Ruim/resource.json"), "{}");
        write(root.resolve("sem-manifest/client/main.lua"), "x");

        List<String> warnings = new ArrayList<>();
        ServerResources resources = ServerResources.scan(root, warnings::add).withKey(BlobCache.newKey());
        assertEquals(List.of("banco", "hud"), List.copyOf(resources.manifest().resources().keySet()));
        assertEquals(2, warnings.size(), warnings.toString());
        // main.lua shared by both, config, app.js, empty.css, and the two resource.json
        assertEquals(6, resources.files().size());
        assertEquals(List.of("banco", "hud", "so-servidor"), List.copyOf(resources.infos().keySet()));

        Path cache = tmp.resolve("cache");
        Result first = sync(resources, cache, UnaryOperator.identity());
        Manifest m = first.manifest;
        assertEquals(List.of("client/main.lua", "resource.json", "shared/config.lua", "ui/app.js", "ui/empty.css"), List.copyOf(m.resources().get("banco").keySet()));
        Protocol.Blob app = m.blobs().get(m.resources().get("banco").get("ui/app.js"));
        assertArrayEquals(big, first.client.cache().read(app));
        // at rest: names say nothing about the content, and the bytes aren't the file
        try (var files = Files.list(cache)) {
            for (Path f : files.toList()) {
                assertTrue(m.blobs().stream().noneMatch(b -> f.getFileName().toString().contains(b.sha256())), f.toString());
                byte[] stored = Files.readAllBytes(f);
                assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("print('oi')"), f.toString());
            }
        }
        assertEquals(m.resources().get("banco").get("client/main.lua"), m.resources().get("hud").get("client/main.lua"));
        assertEquals(5, first.client.downloadedFiles()); // empty.css is created, not downloaded

        Result second = sync(resources, cache, UnaryOperator.identity());
        assertEquals(m, second.manifest);
        assertEquals(0, second.client.downloadedFiles());

        // another key (another server, or a new key): nothing readable, everything downloads again
        Result other = sync(resources.withKey(BlobCache.newKey()), cache, UnaryOperator.identity());
        assertEquals(5, other.client.downloadedFiles());
        assertThrows(IOException.class, () -> new BlobCache(cache, BlobCache.newKey()).read(app));
    }

    @Test
    void tamperedCacheFilesDontLoad() throws IOException {
        Path root = tmp.resolve("resources");
        write(root.resolve("a/resource.json"), "{}");
        write(root.resolve("a/client/main.lua"), "print(1)");
        ServerResources resources = ServerResources.scan(root, w -> {}).withKey(BlobCache.newKey());
        Path cache = tmp.resolve("cache");
        Result r = sync(resources, cache, UnaryOperator.identity());
        Protocol.Blob main = r.manifest.blobs().get(r.manifest.resources().get("a").get("client/main.lua"));
        assertEquals("print(1)", new String(r.client.cache().read(main)));
        try (var files = Files.list(cache)) {
            for (Path f : files.toList()) {
                byte[] b = Files.readAllBytes(f);
                b[b.length - 1] ^= 1;
                Files.write(f, b);
            }
        }
        assertThrows(IOException.class, () -> r.client.cache().read(main));
    }

    @Test
    void rejectsCorruptedFiles() throws IOException {
        Path root = tmp.resolve("resources");
        write(root.resolve("a/resource.json"), "{}");
        write(root.resolve("a/client/main.lua"), "print(1)");
        ServerResources resources = ServerResources.scan(root, w -> {}).withKey(BlobCache.newKey());
        UnaryOperator<byte[]> flipLastByte = msg -> {
            if (msg[0] != Protocol.CHUNK) return msg;
            byte[] copy = Arrays.copyOf(msg, msg.length);
            copy[copy.length - 1] ^= 1;
            return copy;
        };
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> sync(resources, tmp.resolve("cache"), flipLastByte));
        assertInstanceOf(ProtocolException.class, e.getCause());
        try (var files = Files.list(tmp.resolve("cache"))) {
            assertEquals(0, files.count(), "nothing bad stays in the cache");
        }
    }

    @Test
    void downloadsOverHttpAndFallsBackToTheConnection() throws IOException {
        Path root = tmp.resolve("resources");
        byte[] big = new byte[300_000];
        new Random(2).nextBytes(big);
        write(root.resolve("a/resource.json"), "{}");
        write(root.resolve("a/client/main.lua"), "print(1)");
        write(root.resolve("a/server/main.lua"), "-- secret");
        write(root.resolve("a/ui/big.bin"), big);
        int[] chunks = new int[1];
        UnaryOperator<byte[]> countChunks = msg -> {
            if (msg[0] == Protocol.CHUNK) chunks[0]++;
            return msg;
        };
        try (BlobServer http = new BlobServer(0)) {
            ServerResources resources = ServerResources.scan(root, w -> {}).withKey(BlobCache.newKey())
                    .withDownload("http://127.0.0.1:" + http.port() + "/");
            http.serve(resources);
            Result r = sync(resources, tmp.resolve("cache"), countChunks);
            assertEquals(0, chunks[0], "everything came over HTTP");
            assertEquals(3, r.client.downloadedFiles());
            assertEquals(r.client.wantedBytes(), r.client.downloadedBytes());
            assertArrayEquals(big, r.client.cache().read(r.manifest.blobs().get(r.manifest.resources().get("a").get("ui/big.bin"))));

            // the server only knows the published blobs: no path reaches server code
            var secret = java.net.URI.create("http://127.0.0.1:" + http.port() + "/../a/server/main.lua").toURL().openConnection();
            assertEquals(404, ((java.net.HttpURLConnection) secret).getResponseCode());

            http.serve(ServerResources.EMPTY); // now every request is a 404: the game connection takes over
            Result fallback = sync(resources, tmp.resolve("cache2"), countChunks);
            assertEquals(4, chunks[0], "three files, the big one in two chunks");
            assertEquals(3, fallback.client.downloadedFiles());
            assertEquals(fallback.client.wantedBytes(), fallback.client.downloadedBytes());
        }
    }

    private static Protocol.In manifest(int version, int min, int trailing) throws ProtocolException {
        Protocol.In in = new Protocol.In(new Protocol.Out(Protocol.MANIFEST).varint(version).varint(min)
                .bytes(new byte[32]).varint(0).varint(0).string("").bytes(new byte[trailing]).toByteArray());
        in.type();
        return in;
    }

    @Test
    void versionsOverlapOrSayWhoMustUpdate() throws IOException {
        int v = Protocol.VERSION;
        Protocol.decodeManifest(manifest(v, Protocol.MIN_VERSION, 0));
        Protocol.decodeManifest(manifest(v + 1, v, 5)); // newer server that still speaks ours: its extra fields are skipped
        assertThrows(ProtocolException.class, () -> Protocol.decodeManifest(manifest(v, Protocol.MIN_VERSION, 5)), "same version: trailing bytes are an error");
        assertTrue(assertThrows(ProtocolException.class, () -> Protocol.decodeManifest(manifest(v + 2, v + 1, 0))).getMessage().contains("update the mod"));
        assertTrue(assertThrows(ProtocolException.class, () -> Protocol.decodeManifest(manifest(Protocol.MIN_VERSION - 1, 1, 0))).getMessage().contains("too old"));

        ResourceInfo r = ResourceInfo.parse("r", "{ \"troupe\": \">=0.2.1\" }");
        assertTrue(r.runsOn("0.2.1") && r.runsOn("0.3.0+26.3") && r.runsOn("1.0.0") && r.runsOn(null));
        assertFalse(r.runsOn("0.2.0") || r.runsOn("0.1.9+1.20.1"));
        assertTrue(ResourceInfo.parse("r", "{}").runsOn("0.0.1"));
        assertThrows(IOException.class, () -> ResourceInfo.parse("r", "{ \"troupe\": \"latest\" }"));
    }

    @Test
    void rejectsPathsOutsideClientFolders() {
        assertTrue(Protocol.validPath("ui/img/logo.png"));
        for (String bad : List.of("server/main.lua", "client/../server/x.lua", "/client/x", "client\\x", "client/C:x", "ui", "ui/"))
            assertFalse(Protocol.validPath(bad), bad);
    }

    private record Result(Manifest manifest, ClientSync client) {}

    /** Runs one connection to the end; {@code toClient} can tamper with what the server sends. */
    private static Result sync(ServerResources resources, Path cache, UnaryOperator<byte[]> toClient) {
        java.util.concurrent.BlockingQueue<Runnable> wire = new java.util.concurrent.LinkedBlockingQueue<>(); // the HTTP thread adds too
        Manifest[] done = new Manifest[1];
        boolean[] serverDone = new boolean[1];
        ServerSync[] server = new ServerSync[1];
        ClientSync[] c = new ClientSync[1];
        java.util.function.Consumer<byte[]> toTheClient = msg -> wire.add(() -> {
            Manifest m = io(() -> c[0].receive(msg));
            if (m != null) {
                done[0] = m;
                c[0].ready();
            }
        });
        ClientSync client = c[0] = new ClientSync(cache, msg -> wire.add(() -> serverDone[0] |= io(() -> server[0].receive(msg))), toTheClient);
        server[0] = new ServerSync(resources, new ServerSync.Sender() {
            public void send(byte[] msg, Runnable onSent) {
                toTheClient.accept(toClient.apply(msg));
                if (onSent != null) wire.add(onSent);
            }

            public void disconnect(String reason) {
                fail(reason);
            }
        });
        server[0].start();
        while (!serverDone[0] || done[0] == null) {
            Runnable next;
            try {
                next = wire.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            assertNotNull(next, "the download stalled");
            next.run();
        }
        return new Result(done[0], client);
    }

    private interface IoCall<T> {
        T call() throws IOException;
    }

    private static <T> T io(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String s) throws IOException {
        write(p, s.getBytes());
    }

    private static void write(Path p, byte[] b) throws IOException {
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }
}

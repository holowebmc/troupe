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
        ServerResources resources = ServerResources.scan(root, warnings::add);
        assertEquals(List.of("banco", "hud"), List.copyOf(resources.manifest().resources().keySet()));
        assertEquals(2, warnings.size(), warnings.toString());
        assertEquals(4, resources.files().size()); // main.lua shared by both, config, app.js, empty.css

        Path cache = tmp.resolve("cache");
        Result first = sync(resources, cache, UnaryOperator.identity());
        Manifest m = first.manifest;
        assertEquals(List.of("client/main.lua", "shared/config.lua", "ui/app.js", "ui/empty.css"), List.copyOf(m.resources().get("banco").keySet()));
        assertArrayEquals(big, Files.readAllBytes(cache.resolve(m.blobs().get(m.resources().get("banco").get("ui/app.js")).sha256())));
        assertEquals(m.resources().get("banco").get("client/main.lua"), m.resources().get("hud").get("client/main.lua"));
        assertEquals(3, first.client.downloadedFiles()); // empty.css is created, not downloaded

        Result second = sync(resources, cache, UnaryOperator.identity());
        assertEquals(m, second.manifest);
        assertEquals(0, second.client.downloadedFiles());
    }

    @Test
    void rejectsCorruptedFiles() throws IOException {
        Path root = tmp.resolve("resources");
        write(root.resolve("a/resource.json"), "{}");
        write(root.resolve("a/client/main.lua"), "print(1)");
        ServerResources resources = ServerResources.scan(root, w -> {});
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
    void rejectsPathsOutsideClientFolders() {
        assertTrue(Protocol.validPath("ui/img/logo.png"));
        for (String bad : List.of("server/main.lua", "client/../server/x.lua", "/client/x", "client\\x", "client/C:x", "ui", "ui/"))
            assertFalse(Protocol.validPath(bad), bad);
    }

    private record Result(Manifest manifest, ClientSync client) {}

    /** Runs one connection to the end; {@code toClient} can tamper with what the server sends. */
    private static Result sync(ServerResources resources, Path cache, UnaryOperator<byte[]> toClient) {
        Queue<Runnable> wire = new ArrayDeque<>();
        Manifest[] done = new Manifest[1];
        boolean[] serverDone = new boolean[1];
        ServerSync[] server = new ServerSync[1];
        ClientSync client = new ClientSync(cache, msg -> wire.add(() -> serverDone[0] |= io(() -> server[0].receive(msg))));
        server[0] = new ServerSync(resources, new ServerSync.Sender() {
            public void send(byte[] msg, Runnable onSent) {
                wire.add(() -> {
                    Manifest m = io(() -> client.receive(toClient.apply(msg)));
                    if (m != null) done[0] = m;
                });
                if (onSent != null) wire.add(onSent);
            }

            public void disconnect(String reason) {
                fail(reason);
            }
        });
        server[0].start();
        while (!wire.isEmpty()) wire.poll().run();
        assertTrue(serverDone[0], "server got READY");
        assertNotNull(done[0], "client finished");
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

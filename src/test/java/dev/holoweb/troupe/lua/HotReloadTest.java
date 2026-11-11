package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.Protocol.Manifest;
import dev.holoweb.troupe.net.ServerResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A server restarts resources while a player is connected: the glue's steps (rescan, lifecycle, publish = only the
 * running ones, push) with real files, and the client downloading and restarting its side.
 */
class HotReloadTest {
    @TempDir
    Path tmp;

    private final Queue<Runnable> wire = new ArrayDeque<>();
    private final List<Object> client = new ArrayList<>();
    private ServerResources resources = ServerResources.EMPTY, published = ServerResources.EMPTY;

    private void write(String path, String text) throws Exception {
        Path p = tmp.resolve("resources").resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private void pump() {
        Runnable r;
        while ((r = wire.poll()) != null) r.run();
    }

    @Test
    void connectedClientsFollowRestartsStopsAndStarts() throws Exception {
        ResourceHost server = new ResourceHost(Side.SERVER, Runnable::run);
        ResourceHost clientHost = new ResourceHost(Side.CLIENT, Runnable::run);
        clientHost.modules().module("probe").function("see", a -> client.add(a.get(0)));
        Path cache = tmp.resolve("cache");

        Link[] toClient = new Link[1];
        Manifest[] clientManifest = {Manifest.EMPTY};
        Link toServer = new Link(clientHost, NetTest.out(m -> wire.add(() -> NetTest.deliver(toClient[0], m))),
                () -> clientHost.apply(ResourceHost.entries(clientManifest[0], cache), clientManifest[0].generations()),
                cache, m -> {
                    clientManifest[0] = m;
                    clientHost.apply(ResourceHost.entries(m, cache), m.generations());
                });
        toClient[0] = new Link(server, new Peer("p1", "Ana"), NetTest.out(m -> wire.add(() -> NetTest.deliver(toServer, m))));
        Links links = new Links(server);
        server.setNetwork(links);

        write("hud/resource.json", "{}");
        write("hud/client/main.lua", "mods.call('probe', 'see', 'v1'); on_stop(function() mods.call('probe', 'see', 'v1 down') end)");
        write("hud/server/main.lua", "");
        Runnable rescan = () -> {
            try {
                resources = ServerResources.scan(tmp.resolve("resources"), w -> {});
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            server.setAvailable(ResourceHost.entries(resources));
        };
        Runnable publish = () -> {
            ServerResources now = resources.only(server.running());
            if (now.manifest().equals(published.manifest())) return;
            published = now;
            links.push(now);
        };

        rescan.run();
        server.startAll();
        publish.run();
        links.add(toClient[0]);   // a player joins and is already up to date: START only
        toClient[0].push(published); // the glue pushes when the join-time manifest differs; here it's the first one
        pump();
        assertEquals(List.of("v1"), client);

        write("hud/client/main.lua", "mods.call('probe', 'see', 'v2')");
        rescan.run();
        server.restart("hud");
        publish.run();
        pump();
        assertEquals(List.of("v1", "v1 down", "v2"), client, "edited and restarted on the server: the client runs v2");

        server.restart("hud"); // same files, new generation: the client restarts too
        publish.run();
        pump();
        assertEquals(List.of("v1", "v1 down", "v2", "v2"), client);

        server.stop("hud");
        publish.run();
        pump();
        assertFalse(clientHost.isRunning("hud"), "stopped on the server: stopped on the client");

        server.start("hud");
        publish.run();
        pump();
        assertTrue(clientHost.isRunning("hud"));
        assertEquals("v2", client.get(client.size() - 1));
    }
}

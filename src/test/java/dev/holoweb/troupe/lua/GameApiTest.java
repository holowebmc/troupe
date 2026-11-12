package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Built-in global modules as the game glue registers them (with fakes for what touches Minecraft), and Store. */
class GameApiTest {
    private final List<String> log = new ArrayList<>();

    private static void start(ResourceHost host, String side, String main) throws Exception {
        host.setAvailable(Map.of("r", new ResourceHost.Entry(ResourceInfo.parse("r", "{}"),
                Map.of(side + "/main.lua", main.getBytes(StandardCharsets.UTF_8))::get)));
        host.start("r");
    }

    @Test
    void globalModulesAndPlayerMethods() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        Module players = host.modules().global("players")
                .function("message", a -> log.add(a.string(0) + " <- " + a.string(1)))
                .event("join");
        start(host, "server", """
                players.on_join(function(p)
                  p:message('oi ' .. p.name)
                  p.state.visitas = 1
                  mods.call('players', 'message', p.id, 'pelo mods.call também')
                end)""");
        players.emit("join", new Peer("p1", "Ana"));
        assertEquals(List.of("p1 <- oi Ana", "p1 <- pelo mods.call também"), log);
        assertEquals("1", host.getState("r", "p1", "visitas"));
    }

    @Test
    void entityHandlesGetMethods() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        host.modules().global("entities")
                .function("spawn", a -> Map.of("id", "e1", "type", a.string(0)))
                .function("near", a -> List.of(Map.of("id", "e2", "type", "minecraft:cow")))
                .function("get", a -> a.string(0).equals("e9") ? null : Map.of("id", a.string(0), "type", "minecraft:pig"))
                .function("set_name", a -> log.add(a.string(0) + " named " + a.string(1)))
                .function("remove", a -> {
                    throw new IllegalArgumentException("entity isn't loaded");
                });
        start(host, "server", """
                local z = entities.spawn('minecraft:zombie', 0, 64, 0)
                z:set_name('Zé')
                for _, e in ipairs(entities.near(0, 64, 0, 10)) do e:set_name(e.type) end
                entities.get('e3'):set_name('porco')
                mods.call('entities', 'set_name', tostring(entities.get('e9')), 'nada')
                local ok, err = pcall(function() z:remove() end)
                mods.call('entities', 'set_name', 'err', err)""");
        assertEquals(List.of("e1 named Zé", "e2 named minecraft:cow", "e3 named porco", "nil named nada",
                "err named entities.remove: entity isn't loaded"), log);
    }

    @Test
    void startingResourceCanBeToldWhoIsOnline() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        Module players = host.modules().global("players").function("message", a -> log.add(a.string(1))).event("join");
        host.onStart(r -> host.modules().emitTo(r, players, "join", new Peer("p1", "Ana")));
        start(host, "server", "players.on_join(function(p) p:message('olá de novo, ' .. p.name) end)");
        assertEquals(List.of("olá de novo, Ana"), log);
    }

    @Test
    void keysBindListensToPresses() throws Exception {
        ResourceHost host = new ResourceHost(Side.CLIENT, Runnable::run);
        Module keys = host.modules().global("keys").function("watch", a -> log.add("watch " + a.string(0))).event("press");
        host.modules().module("probe").function("see", a -> log.add(a.string(0)));
        start(host, "client", "keys.bind('b', function() mods.call('probe', 'see', 'b pressed') end)");
        keys.emit("press", "x");
        keys.emit("press", "b");
        assertEquals(List.of("watch b", "b pressed"), log);
    }

    @Test
    void storeKeepsValuesOnDisk(@TempDir Path dir) throws Exception {
        Store store = new Store(dir);
        store.set("banco", "ana", Map.of("saldo", 70L));
        store.set("banco", "bia", 5L);
        store.set("banco", "bia", null);
        assertEquals(List.of("ana"), store.keys("banco"));
        assertFalse(Files.exists(dir.resolve("banco.json")), "nothing is written before flush");
        store.flush();

        Store reloaded = new Store(dir);
        assertEquals(Map.of("saldo", 70L), reloaded.get("banco", "ana"));
        assertNull(reloaded.get("outra", "ana"), "stores are per resource");
        assertThrows(IllegalArgumentException.class, () -> store.set("banco", "x", Double.NaN));
    }
}

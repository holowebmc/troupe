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
    void cancellableEvents() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        Module players = host.modules().global("players").event("break").event("place");
        assertFalse(host.modules().listens(players, "break"));
        start(host, "server", """
                players.on_break(function(p, block) return block.id ~= 'minecraft:bedrock' end)
                players.on_break(function(p, block) if block.id == 'minecraft:stone' then error('oops') end end)
                players.on_place(function(p, block) timer.wait(10) return false end)""");
        assertTrue(host.modules().listens(players, "break"));
        Peer ana = new Peer("p1", "Ana");
        assertFalse(host.modules().emitCancellable(players, "break", ana, Map.of("id", "minecraft:bedrock")), "false cancels");
        assertTrue(host.modules().emitCancellable(players, "break", ana, Map.of("id", "minecraft:dirt")), "nil/true lets it happen");
        assertTrue(host.modules().emitCancellable(players, "break", ana, Map.of("id", "minecraft:stone")), "an error doesn't cancel");
        assertTrue(host.modules().emitCancellable(players, "place", ana, Map.of("id", "minecraft:dirt")), "a handler that waits can't cancel");
    }

    @Test
    void entityHandlesGetMethods() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        host.modules().global("entities")
                .function("spawn", a -> new EntityRef("e1", a.string(0)))
                .function("near", a -> List.of(new EntityRef("e2", "minecraft:cow")))
                .function("get", a -> a.string(0).equals("e9") ? null : new EntityRef(a.string(0), "minecraft:pig"))
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

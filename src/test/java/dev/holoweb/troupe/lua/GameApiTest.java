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
    void zonesAndCameraPaths() throws Exception {
        ResourceHost host = new ResourceHost(Side.SERVER, Runnable::run);
        Zones zones = new Zones();
        Module zone = host.modules().global("zone")
                .function("create", a -> zones.create(a.resource(), "overworld", a.table(0)))
                .function("remove", a -> {
                    zones.remove(a.resource(), a.integer(0));
                    return null;
                })
                .function("players", a -> zones.inside(a.resource(), a.integer(0)).stream().map(id -> new Peer(id, "Ana")).toList())
                .event("enter").event("leave");
        host.modules().global("probe").function("see", a -> log.add(String.valueOf(a.get(0))));
        start(host, "server", """
                local loja = zone.box({ 0, 60, 0 }, { x = 10, y = 70, z = 10 })
                loja:on_enter(function(p, z) probe.see('entrou ' .. p.name .. ' ' .. #z:players()) end)
                loja:on_leave(function(p) probe.see('saiu ' .. p.name) end)
                local bola = zone.sphere({ 100, 64, 100 }, 5)
                bola:on_enter(function(p) probe.see('bola') bola:remove() end)
                probe.see(select(2, pcall(zone.sphere, { 0, 0, 0 }, -1)))
                """);
        Peer ana = new Peer("p1", "Ana");
        Zones.Sink sink = (resource, enter, id) -> host.modules().emitTo(resource, zone, enter ? "enter" : "leave", id, ana);
        zones.update("p1", "overworld", 5, 65, 5, sink);
        zones.update("p1", "overworld", 6, 65, 5, sink);     // still inside: nothing
        zones.update("p1", "nether", 5, 65, 5, sink);        // same numbers, another dimension
        zones.update("p1", "overworld", 100, 64, 103, sink);
        zones.update("p1", "overworld", 500, 64, 500, sink); // the sphere removed itself: no leave
        zones.update("p1", "overworld", 5, 65, 5, sink);
        zones.left("p1", sink);
        assertTrue(log.get(0).contains("radius must be > 0"), log.get(0));
        assertEquals(List.of("entrou Ana 1", "saiu Ana", "bola", "entrou Ana 1", "saiu Ana"), log.subList(1, log.size()));

        CameraPath path = new CameraPath(List.of(new double[]{0, 0, 0, 350, 0}, new double[]{10, 0, 0, 10, 20}, new double[]{10, 0, 10, 10, 20}), false);
        assertArrayEquals(new double[]{0, 0, 0, 350, 0}, path.at(0), 1e-9);
        assertArrayEquals(new double[]{10, 0, 0, 10, 20}, path.at(0.5), 1e-9, "it goes through every point");
        assertArrayEquals(new double[]{10, 0, 10, 10, 20}, path.at(2), 1e-9);
        assertEquals(360, path.at(0.25)[3], 1e-9, "350 to 10 turns through 360, not back through 180");
    }

    /** Runs what other threads hand to the game thread until the script has reported {@code lines} lines. */
    private void pump(java.util.concurrent.BlockingQueue<Runnable> tasks, int lines) throws InterruptedException {
        while (log.size() < lines) {
            Runnable task = tasks.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(task, "no answer: " + log);
            task.run();
        }
    }

    @Test
    void databaseOnTheServer() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("troupe", ".db");
        var tasks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        ResourceHost host = new ResourceHost(Side.SERVER, tasks::add);
        host.modules().global("probe").function("see", a -> log.add(String.valueOf(a.get(0))));
        DbApi db = new DbApi(host.modules());
        db.open("jdbc:sqlite:" + file.toString().replace('\\', '/'), null, null, null);
        start(host, "server", """
                db.execute('CREATE TABLE contas (id INTEGER PRIMARY KEY, nome TEXT, saldo REAL)')
                local r = db.execute('INSERT INTO contas (nome, saldo) VALUES (?, ?)', { 'Ana ção', 10.5 })
                probe.see(r.changes .. ' ' .. r.id)
                db.batch({ { 'INSERT INTO contas (nome, saldo) VALUES (?, ?)', { 'Bia', 3 } }, 'UPDATE contas SET saldo = saldo + 1' })
                local rows = db.query('SELECT nome, saldo FROM contas WHERE saldo > ? ORDER BY id', { 0 })
                probe.see(#rows .. ' ' .. rows[1].nome .. ' ' .. rows[1].saldo .. ' ' .. rows[2].saldo)
                probe.see(select(2, pcall(db.batch, { { 'INSERT INTO contas (nome) VALUES (?)', { 'Cau' } }, 'INSERT INTO nope VALUES (1)' })))
                probe.see(#db.query('SELECT 1 FROM contas'))
                probe.see(db.query('SELECT ? AS x', 'x')[1].x)
                db.execute('CREATE TABLE n (a TEXT, b INTEGER, c TEXT)')
                db.execute('INSERT INTO n VALUES (?, ?, ?)', 'um', nil, nil)                  -- one by one: nil is NULL, even last
                db.execute('INSERT INTO n VALUES (?, ?, ?)', { db.null, 2, db.null })       -- in a list: db.null
                db.execute('INSERT INTO n VALUES (?, ?, ?)', { 'tres', nil, 'fim' })        -- a nil in the middle of a list
                db.batch({ { 'INSERT INTO n VALUES (?, ?, ?)', { 'quatro', db.null, db.null } } })
                local nulls = db.query('SELECT a, b, c FROM n WHERE b IS NULL ORDER BY rowid')
                probe.see(#nulls .. ' ' .. tostring(nulls[1].b) .. ' ' .. tostring(nulls[1].c) .. ' ' .. nulls[2].c .. ' ' .. nulls[3].a)
                probe.see(#db.query('SELECT 1 FROM n WHERE a IS ? AND b = ?', nil, 2))
                """);
        pump(tasks, 7);
        db.close();
        assertEquals("1 1", log.get(0));
        assertEquals("2 Ana ção 11.5 4", log.get(1));
        assertTrue(log.get(2).contains("nope"), log.get(2));
        assertEquals("2", log.get(3), "the failed batch left nothing behind");
        assertEquals("x", log.get(4), "one parameter, not in a list");
        assertEquals("3 nil nil fim quatro", log.get(5), "NULL goes in, and comes back as nil");
        assertEquals("1", log.get(6));
        assertTrue(java.nio.file.Files.size(file) > 0, "the data is on disk");
    }

    @Test
    void httpAndJsonOnTheServer() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("troupe", ".json");
        java.nio.file.Files.writeString(file, "{\"saldo\": 7, \"nome\": \"ação\"}");
        var tasks = new java.util.concurrent.LinkedBlockingQueue<Runnable>(); // answers come from the HTTP client's threads
        ResourceHost host = new ResourceHost(Side.SERVER, tasks::add);
        host.modules().global("probe").function("see", a -> log.add(String.valueOf(a.get(0))));
        try (var http = new dev.holoweb.troupe.net.BlobServer(0)) {
            var blob = new dev.holoweb.troupe.net.Protocol.Blob("abc", java.nio.file.Files.size(file));
            http.serve(new dev.holoweb.troupe.net.ServerResources(
                    new dev.holoweb.troupe.net.Protocol.Manifest(List.of(blob), Map.of(), Map.of()), List.of(file), Map.of(), file.getParent()));
            start(host, "server", """
                    local base = 'http://127.0.0.1:%d/'
                    local r = http.get(base .. 'abc')
                    local data = json.decode(r.body)
                    probe.see(r.status .. ' ' .. data.saldo .. ' ' .. data.nome .. ' ' .. r.headers['content-length'])
                    probe.see(http.post(base .. 'abc', json.encode({ a = 1 })).status)
                    probe.see(select(2, pcall(http.get, 'ftp://x/')))
                    probe.see(select(2, pcall(http.get, 'http://127.0.0.1:1/', { timeout = 2 })) ~= nil)
                    probe.see(json.encode({ 1, 2 }) .. json.encode(json.decode('{"x":[true,2]}')))
                    """.formatted(http.port()));
            pump(tasks, 5);
        }
        assertEquals("200 7 ação " + java.nio.file.Files.size(file), log.get(0));
        assertEquals("404", log.get(1), "the file server only answers GET");
        assertTrue(log.get(2).contains("only http and https"), log.get(2));
        assertEquals("true", log.get(3));
        assertEquals("[1,2]{\"x\":[true,2]}", log.get(4));
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
        Module keys = host.modules().global("keys").function("watch", a -> log.add("watch " + a.string(0))).event("press").event("release");
        host.modules().module("probe").function("see", a -> log.add(a.string(0)));
        start(host, "client", "keys.bind('b', function() mods.call('probe', 'see', 'b pressed') end, "
                + "function() mods.call('probe', 'see', 'b released') end)");
        keys.emit("press", "x");
        keys.emit("press", "b");
        keys.emit("release", "x");
        keys.emit("release", "b");
        assertEquals(List.of("watch b", "b pressed", "b released"), log);
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

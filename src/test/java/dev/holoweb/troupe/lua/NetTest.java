package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

/** A server host and a client host joined by two Links, with a fake clock; scripts report through {@code see}. */
class NetTest {
    private final long[] clock = {0};
    private final Queue<Runnable> wire = new ArrayDeque<>();
    private final List<Object> server = new ArrayList<>(), client = new ArrayList<>();
    private ResourceHost serverHost, clientHost;
    private final Map<String, ResourceHost.Entry> resources = new HashMap<>();
    private Link toClient, toServer;
    private Links links;
    private String clientResource;

    @BeforeEach
    void setUp() {
        serverHost = new ResourceHost(Side.SERVER, Runnable::run, () -> clock[0]);
        clientHost = new ResourceHost(Side.CLIENT, Runnable::run, () -> clock[0]);
        serverHost.modules().module("probe").function("see", a -> server.add(a.get(0)));
        clientHost.modules().module("probe").function("see", a -> client.add(a.get(0)));
        toServer = new Link(clientHost, out(msg -> wire.add(() -> deliver(toClient, msg))), () -> {
            try {
                clientHost.start(clientResource);
            } catch (ResourceException e) {
                throw new AssertionError(e);
            }
        }, null, m -> {});
        toClient = new Link(serverHost, new Peer("p1", "Ana"), out(msg -> wire.add(() -> deliver(toServer, msg))));
        links = new Links(serverHost);
        serverHost.setNetwork(links);
        clientHost.setNetwork(toServer);
    }

    static void deliver(Link to, byte[] msg) {
        try {
            to.receive(msg);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A Link output that hands messages to {@code send}; "written" right away. */
    static Link.Out out(java.util.function.Consumer<byte[]> send) {
        return new Link.Out() {
            public void send(byte[] message, Runnable onSent) {
                send.accept(message);
                if (onSent != null) onSent.run();
            }

            public void disconnect(String reason) {
                throw new AssertionError("disconnected: " + reason);
            }
        };
    }

    private void resource(String name, String json, String... files) throws IOException {
        Map<String, byte[]> map = new HashMap<>();
        String see = "local see = mods.get('probe').see\n";
        for (int i = 0; i < files.length; i += 2) map.put(files[i], (see + files[i + 1]).getBytes(StandardCharsets.UTF_8));
        resources.put(name, new ResourceHost.Entry(ResourceInfo.parse(name, json), map::get));
        serverHost.setAvailable(resources);
        clientHost.setAvailable(resources);
    }

    /** The order of a real join: the server runs, the player joins, the client starts its side on START. */
    private void startBoth(String name) throws ResourceException {
        serverHost.start(name);
        clientResource = name;
        links.add(toClient);
        pump();
    }

    private void pump() {
        Runnable r;
        while ((r = wire.poll()) != null) r.run();
    }

    private void advance(long ms) {
        clock[0] += ms * 1_000_000;
        serverHost.tick();
        clientHost.tick();
        pump();
    }

    @Test
    void callsReachValidatedHandlersAndReturn() throws Exception {
        resource("banco", "{}",
                "server/main.lua", """
                        local saldo = 100
                        net.handle('sacar', { valor = 'number' }, function(player, args)
                          if args.valor > saldo then error('saldo insuficiente', 0) end
                          timer.wait(500) -- the reply waits; nothing blocks
                          saldo = saldo - args.valor
                          return { saldo = saldo, quem = player.name }
                        end)""",
                "client/main.lua", """
                        local r = net.call('sacar', { valor = 30 })
                        see(r.saldo .. ' ' .. r.quem)
                        see(select(2, pcall(net.call, 'sacar', { valor = 1000 })))
                        see(select(2, pcall(net.call, 'sacar', { valor = 'muito' })))
                        see(select(2, pcall(net.call, 'sacar', { valor = 1, extra = true })))
                        see(select(2, pcall(net.call, 'nada', {})))""");
        startBoth("banco");
        assertEquals(List.of(), client, "the first call waits for the handler's timer");
        advance(500);
        assertEquals("70 Ana", client.get(0));
        assertTrue(client.get(1).toString().endsWith("saldo insuficiente"), client.get(1).toString());
        assertTrue(client.get(2).toString().contains("bad arguments"), client.get(2).toString());
        assertTrue(client.get(3).toString().contains("bad arguments"), client.get(3).toString());
        assertTrue(client.get(4).toString().contains("no handler 'nada'"), client.get(4).toString());
    }

    @Test
    void serverHandlersMustDeclareTheirArguments() throws Exception {
        resource("x", "{}", "server/main.lua", "net.handle('y', function() end)");
        ResourceException e = assertThrows(ResourceException.class, () -> serverHost.start("x"));
        assertTrue(e.getMessage().contains("format of the arguments"), e.getMessage());
    }

    @Test
    void eventsAndCallsFromTheServer() throws Exception {
        resource("chat", "{}",
                "server/main.lua", """
                        net.handle('pronto', {}, function(player)
                          net.emit(player, 'oi', { texto = 'olá ' .. player.name })
                          net.broadcast('todos', 7)
                          see(net.call(player, 'quanto', 2))
                        end)""",
                "client/main.lua", """
                        net.handle('oi', function(args) see(args.texto) end)
                        net.handle('todos', function(n) see(n) end)
                        net.handle('quanto', function(n) return n * 21 end)
                        net.emit('pronto')""");
        startBoth("chat");
        assertEquals(List.of("olá Ana", 7L), client);
        assertEquals(List.of(42L), server);
    }

    @Test
    void stateReplicatesAndIsReadOnlyOnTheClient() throws Exception {
        resource("hud", "{}",
                "server/main.lua", """
                        state.global.hora = 12
                        net.handle('dinheiro', {}, function(player)
                          player.state.saldo = 50
                          see(player.state.saldo)
                        end)""",
                "client/main.lua", """
                        state.watch('saldo', function(v) see('saldo ' .. tostring(v)) end)
                        see(state.global.hora)
                        see(select(2, pcall(function() state.player.saldo = 1 end)))
                        net.emit('dinheiro')""");
        startBoth("hud");
        assertEquals(50L, server.getFirst());
        assertEquals(12L, client.get(0));
        assertTrue(client.get(1).toString().contains("read-only"), client.get(1).toString());
        assertEquals("saldo 50", client.get(2));

        serverHost.stop("hud");
        pump();
        assertEquals("saldo nil", client.get(3), "stopping on the server drops the client's copy");
    }

    @Test
    void exportsRunInTheirOwnStateAndCanWait() throws Exception {
        resource("lib", "{}", "client/main.lua", """
                local n = 0
                return {
                  inc = function(by) n = n + by; return n end,
                  slow = function() timer.wait(100); return 'pronto' end,
                  back = function() return resource('app').ping() end,
                }""");
        resource("app", "{ \"dependencies\": [\"lib\"] }", "client/main.lua", """
                local lib = resource('lib')
                see(lib.inc(2) + lib.inc(3))
                see(select(2, pcall(lib.nope)))
                async(function() see(lib.slow()) end)
                see(select(2, pcall(resource('ninguem').x)))
                return { ping = function() return 'pong' end }""");
        clientHost.start("app");
        assertEquals(7L, client.get(0)); // 2 + 5
        assertTrue(client.get(1).toString().contains("no export 'nope'"));
        assertTrue(client.get(2).toString().contains("isn't running"));
        advance(100);
        assertEquals("pronto", client.get(3));

        resource("ciclo", "{}", "client/main.lua", "see(resource('lib').back())");
        clientHost.start("ciclo");
        assertEquals("pong", client.get(4), "app → lib → app works: callers wait instead of nesting");
    }

    @Test
    void timers() throws Exception {
        resource("t", "{}", "client/main.lua", """
                timer.after(100, function() see('after') end)
                local n = 0
                local id
                id = timer.every(50, function() n = n + 1; see('every ' .. n); if n == 3 then timer.cancel(id) end end)
                local c = timer.after(10, function() see('cancelled ran') end)
                timer.cancel(c)
                see(select(2, pcall(coroutine.wrap(function() timer.wait(1) end))))
                async(function() coroutine.yield() end)""");
        clientHost.start("t");
        assertTrue(client.getFirst().toString().contains("can't wait inside coroutine"), client.getFirst().toString());
        for (int i = 0; i < 6; i++) advance(25);
        assertEquals(List.of("every 1", "after", "every 2", "every 3"), client.subList(1, client.size()));
        advance(200);
        assertEquals(5, client.size(), "every stopped after cancel");
    }

    @Test
    void floodingClientIsRateLimited() throws Exception {
        resource("f", "{}",
                "server/main.lua", "local n = 0; net.handle('x', {}, function() n = n + 1; see(n) end)",
                "client/main.lua", "for i = 1, 100 do net.emit('x') end");
        startBoth("f");
        assertEquals((int) Link.BURST, server.size());
        advance(1000); // refills RATE tokens a second
        clientHost.restart("f");
        pump();
        assertEquals((int) (Link.BURST + Link.RATE), server.size());
    }
}

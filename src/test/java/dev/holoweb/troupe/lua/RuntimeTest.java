package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Callback;
import dev.holoweb.troupe.api.Module;
import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Resources run for real, in memory: scripts report back through a {@code probe} module. */
class RuntimeTest {
    private final List<Object> seen = new ArrayList<>();
    private final Map<String, ResourceHost.Entry> resources = new HashMap<>();
    private ResourceHost host;
    private Module probe;
    private Callback saved;

    @BeforeEach
    void setUp() {
        host = new ResourceHost(Side.CLIENT, Runnable::run);
        probe = host.modules().module("probe")
                .function("see", a -> {
                    seen.add(a.get(0));
                    return null;
                })
                .function("add", a -> a.number(0) + a.number(1))
                .function("echo", a -> a.get(0))
                .function("keep", a -> {
                    saved = a.callback(0);
                    return null;
                })
                .event("ping");
    }

    private void resource(String name, String json, String... files) throws IOException {
        Map<String, byte[]> map = new HashMap<>();
        for (int i = 0; i < files.length; i += 2) map.put(files[i], files[i + 1].getBytes(StandardCharsets.UTF_8));
        resources.put(name, new ResourceHost.Entry(ResourceInfo.parse(name, json), map::get));
        host.setAvailable(resources);
    }

    private void run(String main) throws Exception {
        resource("r", "{}", "client/main.lua", "local see = mods.get('probe').see\n" + main);
        host.start("r");
    }

    @Test
    void sandboxHasNoWayOut() throws Exception {
        run("""
                see(io == nil and os == nil and debug == nil and luajava == nil and dofile == nil and loadfile == nil)
                see(select(2, load('\\27Lua binary')))
                see(pcall(string.dump, function() end))
                see(select(2, pcall(require, 'server.main')))
                see(select(2, pcall(require, 'client/../x')))
                """);
        assertEquals(true, seen.get(0));
        assertTrue(seen.get(1).toString().contains("binary chunk"), seen.get(1).toString());
        assertEquals(false, seen.get(2));
        assertTrue(seen.get(3).toString().contains("not found"), seen.get(3).toString());
        assertTrue(seen.get(4).toString().contains("not found"), seen.get(4).toString());
    }

    @Test
    void requireLoadsOncePerResource() throws Exception {
        resource("r", "{}",
                "client/main.lua", "local a = require('shared.config'); local b = require('shared.config'); mods.call('probe', 'see', a == b and a.coin)",
                "shared/config.lua", "mods.call('probe', 'see', 'loaded'); return { coin = 'R$ ção' }");
        host.start("r");
        assertEquals(List.of("loaded", "R$ ção"), seen);
    }

    @Test
    void requireCycleIsAnError() throws Exception {
        resource("r", "{}", "client/main.lua", "require('client.a')", "client/a.lua", "require('client.b')", "client/b.lua", "require('client.a')");
        ResourceException e = assertThrows(ResourceException.class, () -> host.start("r"));
        assertTrue(e.getMessage().contains("require cycle"), e.getMessage());
        assertFalse(host.isRunning("r"));
    }

    @Test
    void runawayScriptIsStoppedEvenInsidePcall() throws Exception {
        long t0 = System.nanoTime();
        ResourceException e = assertThrows(ResourceException.class, () -> run("while true do pcall(function() while true do end end) end"));
        assertTrue(e.getMessage().contains("without waiting or returning"), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1e6 < LuaRuntime.TIME_LIMIT_MS + 1500);
    }

    @Test
    void modulesCallsTablesAndErrors() throws Exception {
        run("""
                see(mods.has('probe') and not mods.has('nope'))
                see(mods.call('probe', 'add', 2, 3))
                local t = mods.call('probe', 'echo', { 1, 2, { x = 'y' } })
                see(#t == 3 and t[3].x == 'y')
                see(select(2, pcall(mods.call, 'probe', 'add', 'a')))
                see(select(2, pcall(mods.call, 'nope', 'x')))
                see(mods.call('probe', 'echo', 'ação') == 'ação')
                """);
        assertEquals(true, seen.get(5), "UTF-8 survives the trip to Java and back");
        assertEquals(true, seen.get(0));
        assertEquals(5L, seen.get(1));
        assertEquals(true, seen.get(2));
        assertTrue(seen.get(3).toString().contains("bad argument #1 (number expected, got string)"), seen.get(3).toString());
        assertTrue(seen.get(4).toString().contains("no module 'nope'"), seen.get(4).toString());
    }

    @Test
    void eventsAndCallbacksRunAfterTheCurrentCall() throws Exception {
        run("""
                mods.on('probe', 'ping', function(n) see('ping ' .. n) end)
                mods.call('probe', 'keep', function(a, b) see(a + b) end)
                see('main done')
                """);
        probe.emit("ping", 7);
        saved.invoke(1, 2);
        assertEquals(List.of("main done", "ping 7", 3L), seen);

        host.stop("r");
        probe.emit("ping", 8);
        saved.invoke(1, 2);
        assertEquals(3, seen.size(), "nothing runs after stop");
    }

    @Test
    void dependenciesStartFirstAndStopLast() throws Exception {
        resource("base", "{}", "client/main.lua", "mods.call('probe', 'see', 'base up'); on_stop(function() mods.call('probe', 'see', 'base down') end)");
        resource("app", "{ \"dependencies\": [\"base\", \"server-only\"] }",
                "client/main.lua", "mods.call('probe', 'see', 'app up'); on_stop(function() mods.call('probe', 'see', 'app down') end)");
        host.start("app"); // server-only isn't on the client: skipped
        assertEquals(List.of("base up", "app up"), seen);

        seen.clear();
        host.restart("base");
        assertEquals(List.of("app down", "base down", "base up", "app up"), seen);

        seen.clear();
        assertEquals(List.of("app", "base"), host.stop("base"));
        assertEquals(List.of("app down", "base down"), seen);
    }

    @Test
    void missingModuleOrCycleKeepsResourceStopped() throws Exception {
        resource("needs", "{ \"mods\": { \"client\": [\"create\"] } }", "client/main.lua", "error('must not run')");
        assertTrue(assertThrows(ResourceException.class, () -> host.start("needs")).getMessage().contains("'create'"));
        resource("a", "{ \"dependencies\": [\"b\"] }");
        resource("b", "{ \"dependencies\": [\"a\"] }");
        assertTrue(assertThrows(ResourceException.class, () -> host.start("a")).getMessage().contains("cycle"));
        assertFalse(host.isRunning("needs") || host.isRunning("a") || host.isRunning("b"));
    }
}

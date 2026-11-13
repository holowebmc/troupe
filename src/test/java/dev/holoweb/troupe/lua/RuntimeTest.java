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

    private List<String> faultsWith(LuaRuntime.Limits limits) {
        List<String> faults = new ArrayList<>();
        host.setLimits(limits);
        host.onFault((name, why) -> faults.add(name + ": " + why));
        return faults;
    }

    @Test
    void resourceOverItsCpuBudgetIsStopped() throws Exception {
        List<String> faults = faultsWith(new LuaRuntime.Limits(200, 10_000, 300, 256L << 20, 1L << 30));
        run("for i = 1, 5 do async(function() while true do end end) end  see('main done')");
        assertTrue(host.isRunning("r"), "each call is only cut; the resource stops on the host's tick");
        host.tick();
        assertFalse(host.isRunning("r"));
        assertTrue(faults.get(0).startsWith("r: ran for more than 300 ms"), faults.toString());
        host.startAll();
        host.apply(resources, Map.of("r", 0));
        assertFalse(host.isRunning("r"), "it stays stopped until someone starts it by name or the server restarts it");
        host.apply(resources, Map.of("r", 2));
        assertTrue(host.isRunning("r"), "a new generation on the server: it gets another chance");
    }

    @Test
    void allocatingTooMuchStopsTheResource() {
        List<String> faults = faultsWith(new LuaRuntime.Limits(2000, 10_000, 3000, 8L << 20, 1L << 30));
        long t0 = System.nanoTime();
        ResourceException growing = assertThrows(ResourceException.class,
                () -> run("local t = {} for i = 1, 1e9 do t[i] = ('x'):rep(1000) .. i end"));
        assertTrue(growing.getMessage().contains("memory"), growing.getMessage());
        assertTrue((System.nanoTime() - t0) / 1e6 < 1500, "cut by allocation, long before the time limit");
        // one big allocation, which may return before the watchdog looks: then the count at the end of the step catches it
        ResourceException atOnce = assertThrows(ResourceException.class, () -> run("x = ('x'):rep(32 * 1024 * 1024)"));
        assertTrue(atOnce.getMessage().contains("memory") || atOnce.getMessage().contains("8 MB in one call"), atOnce.getMessage());
        assertFalse(host.isRunning("r"));
        assertTrue(faults.isEmpty(), "main.lua failing is a start error, not a fault");
    }

    @Test
    void windowAllocationBudgetAndHeapGuard() throws Exception {
        List<String> faults = faultsWith(new LuaRuntime.Limits(2000, 10_000, 3000, 256L << 20, 16L << 20));
        run("timer.after(0, function() for i = 1, 40 do local s = ('x'):rep(1024 * 1024) end end)");
        host.tick();
        host.tick();
        assertTrue(faults.get(0).startsWith("r: allocated more than 16 MB in 10 s"), faults.toString());

        faults.clear();
        host.setLimits(LuaRuntime.Limits.DEFAULT);
        double[] pressure = {0.5};
        host.heapPressure = () -> pressure[0];
        resource("small", "{}", "client/main.lua", "x = 1");
        run("for i = 1, 100 do local s = ('x'):rep(1024 * 1024) end"); // 100 MB of garbage: under every limit
        host.start("small");
        for (int i = 0; i < 40; i++) host.tick();
        assertTrue(host.isRunning("r"), "no pressure, nothing stops");
        pressure[0] = 0.9;
        for (int i = 0; i < 20; i++) host.tick();
        assertFalse(host.isRunning("r"), "the heap is full: the biggest allocator goes");
        assertTrue(host.isRunning("small"));
        assertEquals(1, faults.size());
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

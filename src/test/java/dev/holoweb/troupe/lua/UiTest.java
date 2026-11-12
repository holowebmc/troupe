package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code ui.*} against a fake backend that records what Vitrine would be asked. */
class UiTest {
    private final List<String> log = new ArrayList<>();
    private final Map<String, UiBackend.PageHandler> pageHandlers = new HashMap<>();
    private LuaRuntime.Files served;
    private boolean focused;
    private UiBackend.Slots slots;
    private ResourceHost host;

    private final UiBackend fake = new UiBackend() {
        public void serve(String resource, LuaRuntime.Files files) {
            served = files;
            log.add("serve " + resource + " " + (files != null));
        }

        public void slots(String resource, Slots source) {
            slots = source;
            log.add("slots " + resource + " " + (source != null));
        }

        public View screen(String resource, String page) {
            log.add("screen " + resource + " " + page);
            return view();
        }

        public View world(String resource, String page, int w, int h) {
            log.add("world " + page + " " + w + "x" + h);
            return view();
        }

        private View view() {
            return new View() {
                public void send(String json) {
                    log.add("send " + json);
                }

                public void on(String name, PageHandler handler) {
                    pageHandlers.put(name, handler);
                }

                public void visible(boolean visible) {
                    log.add("visible " + visible);
                }

                public void focus(boolean focus) {
                    log.add("focus " + focus);
                    focused = focus;
                }

                public boolean focused() {
                    return focused;
                }

                public void volume(float volume) {
                    log.add("volume " + volume);
                }

                public void place(double x, double y, double z, float yaw, float pitch, float width, boolean billboard, float distance, float volume, float range) {
                    log.add("place " + x + "," + y + "," + z + " yaw " + yaw + " pitch " + pitch + " width " + width + " " + billboard
                            + " distance " + distance + " volume " + volume + " range " + range);
                }

                public void close() {
                    log.add("close");
                }
            };
        }
    };

    @BeforeEach
    void setUp() {
        host = new ResourceHost(Side.CLIENT, Runnable::run);
        host.setUi(fake);
    }

    private void start(String main) throws Exception {
        Map<String, byte[]> files = Map.of(
                "client/main.lua", main.getBytes(StandardCharsets.UTF_8),
                "ui/index.html", "<h1>oi</h1>".getBytes(StandardCharsets.UTF_8));
        host.setAvailable(Map.of("hud", new ResourceHost.Entry(ResourceInfo.parse("hud", "{}"), files::get)));
        host.start("hud");
    }

    @Test
    void pageIsCreatedOnShowAndGetsWhatWasSentBefore() throws Exception {
        start("""
                local v = ui.screen('index.html')
                v:send('saldo', { valor = 10 })
                v:handle('sacar', function(data) return { ok = data.valor < 50 } end)
                v:volume(0.5)
                assert(not v:focused(), 'no page yet')
                v:show()
                v:focus(true)
                assert(v:focused())
                assert(not pcall(v.volume, v, 2))""");
        assertEquals(List.of("serve hud true", "screen hud index.html", "volume 0.5",
                "send {\"troupe\":\"saldo\",\"data\":{\"valor\":10}}", "visible true", "visible true", "focus true"), log);
        assertArrayEquals("<h1>oi</h1>".getBytes(StandardCharsets.UTF_8), served.read("index.html"), "page paths are inside ui/");
        assertTrue(new String(served.read("troupe.js"), StandardCharsets.UTF_8).contains("window.troupe"));
        assertNull(served.read("../client/main.lua"), "only ui/ is reachable (Vitrine refuses .. anyway)");

        String[] reply = new String[1];
        pageHandlers.get("sacar").handle("{\"valor\":30}", new UiBackend.PageReply() {
            public void ok(String json) {
                reply[0] = "ok " + json;
            }

            public void error(String message) {
                reply[0] = "error " + message;
            }
        });
        assertEquals("ok {\"ok\":true}", reply[0]);

        log.clear();
        host.stop("hud");
        assertEquals(List.of("close", "serve hud false"), log, "stopping the resource closes its pages");
    }

    @Test
    void escRunsOnUnfocusOnce() throws Exception {
        start("""
                local v = ui.screen('index.html')
                v:on_unfocus(function() print('perdeu') v:hide() end)
                v:focus(true)""");
        host.tick();
        focused = false; // Esc
        log.clear();
        host.tick();
        host.tick();
        assertEquals(List.of("focus false", "visible false"), log, "on_unfocus ran once");
    }

    @Test
    void slotsAreThingsOrAFunctionAskedEveryFrame() throws Exception {
        start("""
                ui.slot('arma', { item = 'minecraft:diamond_sword', enchanted = true })
                ui.slot('dois', { { inventory = 5 }, { player = true, x = 20 } })
                ui.slot('vivo', function(slot) return { entity_type = slot.args[1], yaw = slot.width } end)
                ui.slot('quebra', function() error('ops') end)
                ui.slot('espera', function() timer.wait(50) return { player = true } end)
                ui.slot('livre', { block = 'minecraft:furnace', state = { lit = true }, fit = false, scale = { 20, 20, 20 },
                  transform = { { 'translate', 4, 0, 0 }, { 'rotate', 'y', 45 } } })
                ui.slot('fora', { player = true })
                ui.slot('fora', nil)
                assert(not pcall(ui.slot, 'x', { item = 'a', player = true }), 'one kind per thing')
                assert(not pcall(ui.slot, 'x', { cor = 1 }))
                assert(not pcall(ui.slot, 'a b', { player = true }))""");
        assertEquals(List.of("slots hud true"), log);
        assertEquals(List.of(Map.of("item", "minecraft:diamond_sword", "enchanted", true)), slots.slot("arma", 64, 64, List.of()));
        assertEquals(2, slots.slot("dois", 64, 64, List.of()).size());
        assertEquals(List.of(Map.of("entity_type", "minecraft:zombie", "yaw", 48L)), slots.slot("vivo", 48, 64, List.of("minecraft:zombie")));
        assertEquals(List.of(List.of("translate", 4L, 0L, 0L), List.of("rotate", "y", 45L)), slots.slot("livre", 64, 64, List.of()).get(0).get("transform"));
        assertEquals(List.of(), slots.slot("quebra", 64, 64, List.of()), "a function that fails shows nothing");
        assertEquals(List.of(), slots.slot("espera", 64, 64, List.of()), "... and so does one that waits");
        assertEquals(List.of(), slots.slot("fora", 64, 64, List.of()));
        assertEquals(List.of(), slots.slot("nunca", 64, 64, List.of()));
        log.clear();
        host.stop("hud");
        assertEquals(List.of("slots hud false"), log);
    }

    @Test
    void worldPanelsAndErrors() throws Exception {
        start("""
                local p = ui.world('index.html', { pos = { 1, 64.5, -3 }, yaw = 90, width = 3, size = { 800, 600 }, billboard = true })
                p:show()
                p:move({ pos = { 2, 64, -3 } })
                p:move({ pitch = 90, billboard = false, distance = 16 })
                p:volume(0.25)
                assert(not pcall(p.move, p, { pos = { 9, 9, 9 }, width = 0 }))
                assert(not pcall(ui.world, 'index.html', { yaw = 0 }), 'pos is required')
                local ok, err = pcall(p.focus, p, true)
                print(err)
                p:close()
                assert(not pcall(p.show, p), 'closed pages refuse')
                assert(not pcall(ui.screen, '../x.html'))""");
        assertEquals(List.of("world index.html 800x600",
                "place 1.0,64.5,-3.0 yaw 90.0 pitch 0.0 width 3.0 true distance 64.0 volume 1.0 range 24.0", "visible true",
                "place 2.0,64.0,-3.0 yaw 90.0 pitch 0.0 width 3.0 true distance 64.0 volume 1.0 range 24.0",
                "place 2.0,64.0,-3.0 yaw 90.0 pitch 90.0 width 3.0 false distance 16.0 volume 1.0 range 24.0",
                "place 2.0,64.0,-3.0 yaw 90.0 pitch 90.0 width 3.0 false distance 16.0 volume 0.25 range 24.0"), log.subList(1, 7));
        assertEquals("close", log.get(log.size() - 1));
    }

    @Test
    void withoutVitrineUiIsAClearError() throws Exception {
        host.setUi(null);
        ResourceException e = assertThrows(ResourceException.class, () -> start("ui.screen('index.html')"));
        assertTrue(e.getMessage().contains("needs the Vitrine mod"), e.getMessage());
    }
}

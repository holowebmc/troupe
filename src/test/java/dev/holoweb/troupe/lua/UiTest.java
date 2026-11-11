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
    private ResourceHost host;

    private final UiBackend fake = new UiBackend() {
        public void serve(String resource, LuaRuntime.Files files) {
            served = files;
            log.add("serve " + resource + " " + (files != null));
        }

        public View screen(String resource, String page) {
            log.add("screen " + resource + " " + page);
            return view();
        }

        public View world(String resource, String page, int w, int h, double x, double y, double z, float yaw, float width, boolean billboard) {
            log.add("world " + page + " " + w + "x" + h + " at " + x + "," + y + "," + z + " yaw " + yaw + " width " + width + " " + billboard);
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
                }

                public void move(double x, double y, double z, float yaw, float width) {
                    log.add("move " + x);
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
                v:show()
                v:focus(true)""");
        assertEquals(List.of("serve hud true", "screen hud index.html",
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
    void worldPanelsAndErrors() throws Exception {
        start("""
                local p = ui.world('index.html', { pos = { 1, 64.5, -3 }, yaw = 90, width = 3, size = { 800, 600 }, billboard = true })
                p:show()
                p:move({ pos = { 2, 64, -3 } })
                local ok, err = pcall(p.focus, p, true)
                print(err)
                p:close()
                assert(not pcall(p.show, p), 'closed pages refuse')
                assert(not pcall(ui.screen, '../x.html'))""");
        assertEquals("world index.html 800x600 at 1.0,64.5,-3.0 yaw 90.0 width 3.0 true", log.get(1));
        assertEquals("move 2.0", log.get(3));
        assertEquals("close", log.get(log.size() - 1));
    }

    @Test
    void withoutVitrineUiIsAClearError() throws Exception {
        host.setUi(null);
        ResourceException e = assertThrows(ResourceException.class, () -> start("ui.screen('index.html')"));
        assertTrue(e.getMessage().contains("needs the Vitrine mod"), e.getMessage());
    }
}

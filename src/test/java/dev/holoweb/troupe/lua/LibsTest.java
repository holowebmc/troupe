package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.ResourceHost.ResourceException;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lua code shared between resources, resource versions, and what Troupe adds to Cobalt's standard library. */
class LibsTest {
    private final List<Object> seen = new ArrayList<>();
    private final Map<String, ResourceHost.Entry> resources = new HashMap<>();
    private final ResourceHost host = new ResourceHost(Side.CLIENT, Runnable::run);

    LibsTest() {
        host.modules().module("probe").function("see", a -> seen.add(a.get(0)));
    }

    private void resource(String name, String json, String... files) throws Exception {
        Map<String, byte[]> map = new HashMap<>();
        for (int i = 0; i < files.length; i += 2) map.put(files[i], files[i + 1].getBytes(StandardCharsets.UTF_8));
        resources.put(name, new ResourceHost.Entry(ResourceInfo.parse(name, json), map::get));
        host.setAvailable(resources);
    }

    @Test
    void aResourceRequiresSharedCodeOfItsDependencies() throws Exception {
        resource("base", "{ \"version\": \"1.4.0\" }",
                "shared/util.lua", "local helper = require('base:shared.helper')  return { double = function(x) return helper.twice(x) end, loads = 0 }",
                "shared/helper.lua", "return { twice = function(x) return x * 2 end }",
                "client/secret.lua", "return 'private'");
        resource("other", "{}", "shared/x.lua", "return 1");
        resource("app", "{ \"dependencies\": [\"base >= 1.2\", \"extras?\"] }", "client/main.lua", """
                local see = mods.get('probe').see
                local util = require('base:shared.util')
                see(util.double(21))
                util.loads = util.loads + 1
                see(require('base:shared.util').loads)                       -- loaded once per resource
                see(select(2, pcall(require, 'base:client.secret')))         -- only shared/ is lent
                see(select(2, pcall(require, 'other:shared.x')))             -- not a dependency
                see(select(2, pcall(require, 'base:shared.missing')))
                see(select(2, pcall(require, 'base:shared/../client/secret')))""");
        host.start("app");
        assertEquals(List.of(42L, 1L), seen.subList(0, 2));
        assertTrue(seen.get(2).toString().contains("only lends its shared.x"), seen.get(2).toString());
        assertTrue(seen.get(3).toString().contains("list 'other' under dependencies"), seen.get(3).toString());
        assertTrue(seen.get(4).toString().contains("not found"), seen.get(4).toString());
        assertTrue(seen.get(5).toString().contains("not found"), seen.get(5).toString());
        assertTrue(host.isRunning("base"), "dependencies start first; the optional one that isn't here is skipped");
    }

    @Test
    void dependenciesMayAskForAVersion() throws Exception {
        resource("base", "{ \"version\": \"1.1\" }", "client/main.lua", "");
        resource("plain", "{}", "client/main.lua", "");
        resource("new", "{ \"dependencies\": [\"base >= 1.2\"] }", "client/main.lua", "");
        resource("blind", "{ \"dependencies\": [\"plain >= 1\"] }", "client/main.lua", "");
        resource("fine", "{ \"dependencies\": [\"base >= 1.1\", \"plain\"] }", "client/main.lua", "");
        assertTrue(assertThrows(ResourceException.class, () -> host.start("new")).getMessage().contains("needs base at version 1.2 or later (this is 1.1)"));
        assertTrue(assertThrows(ResourceException.class, () -> host.start("blind")).getMessage().contains("unversioned"));
        host.start("fine");
        assertTrue(host.isRunning("fine"));
        assertThrows(java.io.IOException.class, () -> ResourceInfo.parse("x", "{ \"version\": \"one two\" }"));
    }

    @Test
    void theStandardLibraryCoversWhatLibrariesExpect() throws Exception {
        resource("r", "{}", "client/main.lua", """
                local see = mods.get('probe').see
                see(bit32.band(0xFF, 0x0F, 0x3C)); see(bit32.bor(1, 2, 4)); see(bit32.bxor(5, 1)); see(bit32.bnot(0))
                see(bit32.lshift(1, 31)); see(bit32.lshift(1, 32)); see(bit32.rshift(0x80000000, 31)); see(bit32.arshift(0x80000000, 31))
                see(bit32.lrotate(0x80000001, 1)); see(bit32.extract(0xABCD, 4, 8)); see(bit32.replace(0, 0xF, 4, 4)); see(bit32.btest(6, 1))
                see(bit32.band(-1, 0xFF)); see(pcall(bit32.extract, 1, 30, 5))
                see(math.type(3)); see(math.type(3.5)); see(math.type('3') == nil); see(math.tointeger(4.0)); see(math.tointeger(4.5) == nil)
                see(math.maxinteger == 2^53); see(math.ult(1, -1))
                see(utf8.len('ação')); see(table.concat(table.move({ 1, 2, 3 }, 1, 3, 2), ','))
                do goto done end ::done:: see('goto')""");
        host.start("r");
        assertEquals(List.of(12L, 7L, 4L, 4294967295L, 2147483648L, 0L, 1L, 4294967295L, 3L, 188L, 240L, false, 255L, false,
                "integer", "float", true, 4L, true, true, true, 4L, "1,1,2,3", "goto"), seen);
    }
}

package dev.holoweb.troupe;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.lua.ResourceHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.compiler.LoadState;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The editor definitions shipped in /troupe/defs follow what is really registered, and are valid Lua. */
class DefsTest {
    /** Defined by the prelude in Lua, on top of a registered module. */
    private static final Set<String> PRELUDE = Set.of("http.get", "http.post", "zone.box", "zone.sphere", "keys.bind");

    private static String defs(String name) throws Exception {
        try (var in = DefsTest.class.getResourceAsStream("/troupe/defs/" + name + ".lua")) {
            assertNotNull(in, name + ".lua is missing");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            LoadState.load(new LuaState(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "@" + name + ".lua", new org.squiddev.cobalt.LuaTable());
            return text;
        }
    }

    private static void follows(ResourceHost host, String text, String file) {
        List<String> registered = host.modules().globalFunctions();
        List<String> missing = new ArrayList<>();
        for (String fn : registered) if (!text.contains("function " + fn + "(")) missing.add(fn);
        assertEquals(List.of(), missing, "registered but not in the definitions");

        Set<String> modules = new HashSet<>();
        for (String fn : registered) modules.add(fn.substring(0, fn.indexOf('.')));
        List<String> invented = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^function ([a-z_]+)\\.([A-Za-z_0-9]+)\\(").matcher(text);
        while (m.find()) {
            String fn = m.group(1) + "." + m.group(2);
            if (modules.contains(m.group(1)) && !registered.contains(fn) && !PRELUDE.contains(fn)) invented.add(fn);
        }
        assertEquals(List.of(), invented, file + " describes functions that don't exist");
    }

    @Test
    void definitionsFollowTheRegisteredApi(@TempDir Path tmp) throws Exception {
        String shared = defs("shared");

        ResourceHost server = new ResourceHost(Side.SERVER, Runnable::run);
        try {
            new ServerApi(server.modules(), server, () -> null);
        } catch (LinkageError e) { // 1.20.1's game classes don't link in a unit test; the registrations are the same source
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "the game API can't be built here: " + e);
        }
        new dev.holoweb.troupe.lua.DbApi(server.modules());
        follows(server, shared + defs("server"), "server.lua");

        ResourceHost client = new ResourceHost(Side.CLIENT, Runnable::run);
        new ClientApi(client.modules());
        new ClientHud(client.modules(), client);
        new WorldDraw(client.modules(), client);
        RenderFx.register(client.modules(), client);
        new ClientKeys(client.modules(), client, tmp.resolve("keys.json"));
        follows(client, shared + defs("client"), "client.lua");
    }

    @Test
    void noClassIsDefinedTwice() throws Exception {
        Set<String> seen = new HashSet<>();
        List<String> twice = new ArrayList<>();
        for (String file : List.of("shared", "server", "client")) {
            Matcher m = Pattern.compile("(?m)^---@(?:class|alias) ([A-Za-z_.0-9]+)").matcher(defs(file));
            while (m.find()) if (!seen.add(m.group(1)) && !Set.of("Player", "Entity").contains(m.group(1))) twice.add(file + ": " + m.group(1));
        }
        assertEquals(List.of(), twice);
    }
}

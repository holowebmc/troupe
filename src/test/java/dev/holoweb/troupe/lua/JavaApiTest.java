package dev.holoweb.troupe.lua;

import dev.holoweb.troupe.api.Side;
import dev.holoweb.troupe.net.AssetPacks;
import dev.holoweb.troupe.net.Protocol.Blob;
import dev.holoweb.troupe.net.Protocol.Manifest;
import dev.holoweb.troupe.net.ResourceInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** {@code java.*} on the server (allow list, always-denied classes, conversions) and the asset pack zip. */
class JavaApiTest {
    private final List<Object> seen = new ArrayList<>();

    private ResourceHost host(Side side, List<String> allow) {
        ResourceHost host = new ResourceHost(side, Runnable::run);
        host.setJavaAllow(allow);
        host.modules().module("probe").function("see", a -> seen.add(a.get(0)));
        return host;
    }

    private void run(ResourceHost host, String main) throws Exception {
        String side = host.side() == Side.SERVER ? "server" : "client";
        host.setAvailable(Map.of("r", new ResourceHost.Entry(ResourceInfo.parse("r", "{}"),
                Map.of(side + "/main.lua", ("local see = mods.get('probe').see\n" + main).getBytes(StandardCharsets.UTF_8))::get)));
        host.start("r");
    }

    @Test
    void allowedPackagesWork() throws Exception {
        run(host(Side.SERVER, List.of("java.util", "java.lang.Math")), """
                local list = java.new('java.util.ArrayList')
                list:add('a'); list:add('b')
                see(list:size())
                see(list:get(1))
                see(tostring(list))
                local Math = java.class('java.lang.Math')
                see(Math.max(2, 7))
                see(Math.PI > 3.14)
                see(java.class('java.util.Collections').emptyList():isEmpty())
                see(select(2, pcall(list.get, list, 5)))
                see(select(2, pcall(java.class, 'java.lang.String')))""");
        assertEquals(2L, seen.get(0));
        assertEquals("b", seen.get(1));
        assertEquals("[a, b]", seen.get(2));
        assertEquals(7L, seen.get(3));
        assertEquals(true, seen.get(4));
        assertEquals(true, seen.get(5));
        assertTrue(seen.get(6).toString().contains("IndexOutOfBoundsException"), seen.get(6).toString());
        assertTrue(seen.get(7).toString().contains("isn't allowed"), seen.get(7).toString());
    }

    @Test
    void dangerousClassesStayDeniedEvenWhenTheirPackageIsAllowed() throws Exception {
        run(host(Side.SERVER, List.of("java.lang", "java")), """
                for _, name in ipairs({ 'java.lang.System', 'java.lang.Runtime', 'java.lang.Class', 'java.lang.ProcessBuilder',
                                        'java.lang.Thread', 'java.lang.reflect.Method', 'java.io.File', 'java.nio.file.Files' }) do
                  see(select(2, pcall(java.class, name)))
                end
                -- a String can't hand out its Class
                see(select(2, pcall(function() return java.new('java.lang.StringBuilder', 'x'):getClass():getName() end)))
                see(pcall(setmetatable, java.new('java.lang.StringBuilder'), {}))""");
        for (int i = 0; i < 8; i++) assertTrue(seen.get(i).toString().contains("isn't allowed"), seen.get(i).toString());
        assertTrue(seen.get(8).toString().contains("isn't allowed"), seen.get(8).toString());
        assertEquals(false, seen.get(9), "handles keep their metatable");
    }

    @Test
    void offByDefaultAndNeverOnTheClient() throws Exception {
        run(host(Side.SERVER, List.of()), "see(select(2, pcall(java.class, 'java.util.ArrayList')))");
        assertTrue(seen.getFirst().toString().contains("java.* is off"), seen.getFirst().toString());
        seen.clear();
        run(host(Side.CLIENT, List.of("java.util")), "see(java == nil)");
        assertEquals(List.of(true), seen);
    }

    @Test
    void assetsBecomeAPackZip(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(cache.resolve("aa"), "png bytes");
        Files.writeString(cache.resolve("bb"), "print(1)");
        Manifest m = new Manifest(List.of(new Blob("aa", 9), new Blob("bb", 8)),
                Map.of("hud", Map.of("assets/hud/textures/icon.png", 0, "client/main.lua", 1), "logic", Map.of("client/main.lua", 1)),
                Map.of());
        Path zip = AssetPacks.build(m, "hud", cache, tmp.resolve("packs"), "{\"pack\":{}}");
        try (ZipFile z = new ZipFile(zip.toFile())) {
            assertNotNull(z.getEntry("pack.mcmeta"));
            assertEquals("png bytes", new String(z.getInputStream(z.getEntry("assets/hud/textures/icon.png")).readAllBytes()));
            assertNull(z.getEntry("client/main.lua"), "only assets/ goes in the pack");
        }
        assertEquals(zip, AssetPacks.build(m, "hud", cache, tmp.resolve("packs"), "{\"pack\":{}}"), "same assets, same zip");
        assertNull(AssetPacks.build(m, "logic", cache, tmp.resolve("packs"), "{}"), "no assets, no pack");
    }
}

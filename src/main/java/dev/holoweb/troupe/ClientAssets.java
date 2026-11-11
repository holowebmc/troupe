package dev.holoweb.troupe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.holoweb.troupe.net.AssetPacks;
import dev.holoweb.troupe.net.Protocol.Manifest;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resource {@code assets/} as server resource packs, pushed locally (no download): textures, models, sounds and lang
 * of a resource apply while it runs. A change reloads the game's resources, like any server pack.
 * <p>
 * They follow the player's "Server Resource Packs" setting for the server: Enabled applies, Disabled skips, Prompt
 * asks once per connection (and the answer counts for the server's own packs too, as vanilla's prompt does).
 * Render thread only.
 */
final class ClientAssets {
    private final Path cache;
    private final Map<String, Path> pushed = new HashMap<>();
    private boolean asked;

    ClientAssets(Path cache) {
        this.cache = cache;
    }

    /** Matches the packs to the server's running resources: new or changed assets push, gone ones pop. */
    void apply(Manifest m) {
        String mcmeta = mcmeta();
        for (String name : List.copyOf(pushed.keySet()))
            if (!m.resources().containsKey(name)) pop(name);
        for (String name : m.resources().keySet()) {
            try {
                Path zip = AssetPacks.build(m, name, cache, cache.resolveSibling("packs"), mcmeta);
                if (zip == null) pop(name);
                else if (!zip.equals(pushed.get(name))) {
                    Minecraft.getInstance().getDownloadedPackSource().pushLocalPack(id(name), zip);
                    pushed.put(name, zip);
                    Troupe.LOG.info("Assets of {} pushed", name);
                }
            } catch (IOException e) {
                Troupe.LOG.error("Couldn't build the assets of {}", name, e);
            }
        }
        askIfNeeded();
    }

    void clear() {
        for (String name : List.copyOf(pushed.keySet())) pop(name);
        asked = false;
    }

    /** Pushed packs wait in the pack manager until server packs are allowed: ask if the player wants to be asked. */
    private void askIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (pushed.isEmpty() || asked || server == null || mc.isLocalServer()) return;
        switch (server.getResourcePackStatus()) {
            case ENABLED -> {}
            case DISABLED -> Troupe.LOG.info("Server resource packs are disabled for this server: Troupe assets stay off");
            case PROMPT -> {
                asked = true;
                Screen previous = mc.gui.screen();
                mc.gui.setScreen(new ConfirmScreen(yes -> {
                    if (yes) mc.getDownloadedPackSource().allowServerPacks();
                    else mc.getDownloadedPackSource().rejectServerPacks();
                    mc.gui.setScreen(previous);
                }, Component.translatableWithFallback("troupe.assets.title", "This server's resources have textures and sounds"),
                        Component.translatableWithFallback("troupe.assets.question", "Apply them? (like a server resource pack)")));
            }
        }
    }

    private void pop(String name) {
        if (pushed.remove(name) != null) Minecraft.getInstance().getDownloadedPackSource().popPack(id(name));
    }

    private static UUID id(String resource) {
        return UUID.nameUUIDFromBytes(("troupe:" + resource).getBytes(StandardCharsets.UTF_8));
    }

    /** 1.21.9+ pack metadata: min/max format as [major, minor] of this game version. */
    private static String mcmeta() {
        PackFormat f = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        JsonArray format = new JsonArray();
        format.add(f.major());
        format.add(f.minor());
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Troupe resource assets");
        pack.add("min_format", format);
        pack.add("max_format", format);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root.toString();
    }
}

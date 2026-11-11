package dev.holoweb.troupe;

//? if >=1.20.3 {
import dev.holoweb.troupe.net.AssetPacks;
import dev.holoweb.troupe.net.Protocol.Manifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

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
        String mcmeta = ClientCompat.packMcmeta();
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
                Screen previous = ClientCompat.screen();
                ClientCompat.setScreen(new ConfirmScreen(yes -> {
                    if (yes) mc.getDownloadedPackSource().allowServerPacks();
                    else mc.getDownloadedPackSource().rejectServerPacks();
                    ClientCompat.setScreen(previous);
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
}
//?} else {
/*import dev.holoweb.troupe.net.AssetPacks;
import dev.holoweb.troupe.net.Protocol.Manifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/^*
 * Resource {@code assets/} as the server resource pack, loaded locally (no download). Before 1.20.3 the game holds
 * one server pack, so every running resource's assets go in one pack.
 * <p>
 * ponytail: the single slot means a server that also sends its own resource pack loses one of the two (the last set
 * wins); per-resource packs need 1.20.3+.
 * <p>
 * They follow the player's "Server Resource Packs" setting for the server: Enabled applies, Disabled skips, Prompt
 * asks once per connection. Render thread only.
 *^/
final class ClientAssets {
    private final Path cache;
    private Path pack;
    /** Null until the player answered the prompt for this connection. *^/
    private Boolean allowed;

    ClientAssets(Path cache) {
        this.cache = cache;
    }

    /^* Rebuilds the one pack from the running resources and loads it if it changed. *^/
    void apply(Manifest m) {
        Path zip;
        try {
            zip = AssetPacks.build(m, List.copyOf(m.resources().keySet()), cache, cache.resolveSibling("packs"), ClientCompat.packMcmeta());
        } catch (IOException e) {
            Troupe.LOG.error("Couldn't build the resource assets", e);
            return;
        }
        if (Objects.equals(zip, pack)) return;
        Path before = pack;
        pack = zip;
        if (zip == null) {
            if (before != null) Minecraft.getInstance().getDownloadedPackSource().clearServerPack();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        ServerData.ServerPackStatus status = server == null || mc.isLocalServer() ? ServerData.ServerPackStatus.ENABLED : server.getResourcePackStatus();
        if (status == ServerData.ServerPackStatus.PROMPT && allowed != null)
            status = allowed ? ServerData.ServerPackStatus.ENABLED : ServerData.ServerPackStatus.DISABLED;
        switch (status) {
            case ENABLED -> load();
            case DISABLED -> Troupe.LOG.info("Server resource packs are disabled for this server: Troupe assets stay off");
            case PROMPT -> {
                Screen previous = ClientCompat.screen();
                ClientCompat.setScreen(new ConfirmScreen(yes -> {
                    allowed = yes;
                    if (yes) load();
                    ClientCompat.setScreen(previous);
                }, Component.translatableWithFallback("troupe.assets.title", "This server's resources have textures and sounds"),
                        Component.translatableWithFallback("troupe.assets.question", "Apply them? (like a server resource pack)")));
            }
        }
    }

    /^* The game clears the server pack itself when leaving the world. *^/
    void clear() {
        pack = null;
        allowed = null;
    }

    private void load() {
        if (pack == null) return;
        Minecraft.getInstance().getDownloadedPackSource().setServerPack(pack.toFile(), PackSource.SERVER);
        Troupe.LOG.info("Resource assets loaded");
    }
}
*///?}

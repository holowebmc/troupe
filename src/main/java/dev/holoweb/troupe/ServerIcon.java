package dev.holoweb.troupe;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;

/**
 * A server's icon for a Troupe window: its favicon from the last ping, or the game's "unknown server" icon. A texture
 * of its own (the server list keeps its own copy); closed with the window.
 */
final class ServerIcon implements AutoCloseable {
    private static final Identifier UNKNOWN = ServerApi.id("minecraft:textures/misc/unknown_server.png");
    private FaviconTexture texture;

    ServerIcon(ServerData server) {
        byte[] bytes = server == null ? null : server.getIconBytes();
        if (bytes == null) return;
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
            texture = FaviconTexture.forServer(Minecraft.getInstance().getTextureManager(), "troupe/" + server.ip);
            texture.upload(image);
        } catch (Exception e) { // not a 64 × 64 PNG: the unknown icon
            Troupe.LOG.debug("Server icon of {} unusable: {}", server.ip, e.toString());
            close();
        }
    }

    Identifier id() {
        return texture == null ? UNKNOWN : texture.textureLocation();
    }

    @Override
    public void close() {
        if (texture != null) texture.close();
        texture = null;
    }
}

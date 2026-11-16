package dev.holoweb.troupe;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client side of {@link Compat}. */
final class ClientCompat {
    private ClientCompat() {}

    static boolean keyDown(int code) {
        //? if >=26.2 {
        return InputConstants.isKeyDown(code);
        //?} else if >=1.21.9 {
        /*return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), code);
        *///?} else
        /*return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);*/
    }

    /** A line in the player's own chat. */
    static void chat(net.minecraft.network.chat.Component message) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        //? if >=26.1 || <1.21.2 {
        player.sendSystemMessage(message);
        //?} else
        /*player.displayClientMessage(message, false);*/
    }

    static int selectedSlot(net.minecraft.world.entity.player.Inventory inv) {
        //? if >=1.21.5 {
        return inv.getSelectedSlot();
        //?} else
        /*return inv.selected;*/
    }

    /** The client sends the new slot to the server on its own (the game mode's tick checks the carried item). */
    static void selectSlot(net.minecraft.world.entity.player.Inventory inv, int slot) {
        //? if >=1.21.5 {
        inv.setSelectedSlot(slot);
        //?} else
        /*inv.selected = slot;*/
    }

    static Screen screen() {
        //? if >=26.2 {
        return Minecraft.getInstance().gui.screen();
        //?} else
        /*return Minecraft.getInstance().screen;*/
    }

    static void setScreen(Screen screen) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.setScreen(screen);
        //?} else
        /*Minecraft.getInstance().setScreen(screen);*/
    }

    /** pack.mcmeta for this game version's client resources. */
    static String packMcmeta() {
        com.google.gson.JsonObject pack = new com.google.gson.JsonObject();
        pack.addProperty("description", "Troupe resource assets");
        //? if >=1.21.9 {
        var f = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
        com.google.gson.JsonArray format = new com.google.gson.JsonArray();
        format.add(f.major());
        format.add(f.minor());
        pack.add("min_format", format);
        pack.add("max_format", format);
        //?} else
        /*pack.addProperty("pack_format", net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES));*/
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("pack", pack);
        return root.toString();
    }

    /** Third person (or render.camera): the camera entity is drawn too. */
    static boolean cameraDetached() {
        //? if >=26.3 {
        return Minecraft.getInstance().gameRenderer.mainCamera().isDetached();
        //?} else
        /*return Minecraft.getInstance().gameRenderer.getMainCamera().isDetached();*/
    }

    /** How far the game is between the last tick and the next, for interpolating positions. */
    static float partialTick() {
        //? if >=1.21.2 {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        //?} else if >=1.21 {
        /*return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        *///?} else
        /*return Minecraft.getInstance().getFrameTime();*/
    }

    /** Packed block and sky light at a position, as entity render types want it. */
    static int light(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        //? if >=26.3 {
        return net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos);
        //?} else if >=26.1 {
        /*return net.minecraft.client.renderer.LevelRenderer.getLightCoords(level, pos);
        *///?} else
        /*return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos);*/
    }

    /** Uploads an image as a texture under {@code id}; the texture owns the image from here. */
    static void registerTexture(net.minecraft.resources.Identifier id, com.mojang.blaze3d.platform.NativeImage image) {
        //? if >=1.21.5 {
        var texture = new net.minecraft.client.renderer.texture.DynamicTexture(id::toString, image);
        //?} else
        /*var texture = new net.minecraft.client.renderer.texture.DynamicTexture(image);*/
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }
}

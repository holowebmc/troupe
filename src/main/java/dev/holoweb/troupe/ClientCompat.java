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
        //?} else
        /*return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), code);*/
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
}

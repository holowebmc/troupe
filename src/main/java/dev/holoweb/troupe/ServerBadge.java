package dev.holoweb.troupe;

import com.google.common.collect.MapMaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A small "✔ troupe" chip after the server's name in the multiplayer list when its ping says it runs Troupe ("✖", red,
 * when it speaks another protocol). Clicking it opens {@link ServerInfoScreen}.
 */
public final class ServerBadge {
    private static final Component LABEL = Component.literal("troupe");
    private static final Component HINT = Component.translatableWithFallback("troupe.badge.hint", "Click for details");
    private static final Map<ServerData, TroupeStatus.Info> infos = new MapMaker().weakKeys().makeMap();
    /** Where each server's chip was last drawn (screen coordinates: x, y, w, h). */
    private static final Map<ServerData, int[]> spots = new MapMaker().weakKeys().makeMap();

    private ServerBadge() {}

    /** From the pinger: the latest status of {@code server} (null info: it doesn't run Troupe, or not anymore). */
    public static void set(ServerData server, TroupeStatus.Info info) {
        if (info == null) infos.remove(server);
        else infos.put(server, info);
    }

    /** Lays the chip out after the name (row content at x, y, {@code width} wide); null when there's none. */
    private static int[] spot(ServerData server, Font font, int x, int y, int width) {
        if (!infos.containsKey(server)) {
            spots.remove(server);
            return null;
        }
        Panel.Chip chip = Panel.Chip.status(LABEL, infos.get(server).compatible(), false);
        int w = chip.width(font);
        int cx = Math.min(x + 32 + 3 + font.width(server.name) + 5, x + width - 60 - w); // clear of the player count
        int[] s = {cx, y, w, chip.height()};
        spots.put(server, s);
        return s;
    }

    private static boolean over(int[] s, double mx, double my) {
        return s != null && mx >= s[0] && mx < s[0] + s[2] && my >= s[1] && my < s[1] + s[3];
    }

    private static void chip(Panel.Canvas c, int[] s, boolean compatible, boolean hover) {
        Panel.Chip.status(LABEL, compatible, hover).draw(c, s[0], s[1]);
    }

    /** {@code x, y}: the row's content corner, where the 32×32 icon starts. */
    public static void draw(GuiGraphicsExtractor g, ServerData server, int x, int y, int width, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        int[] s = spot(server, font, x, y, width);
        if (s == null) return;
        boolean hover = over(s, mouseX, mouseY);
        chip(Panel.canvas(g, font), s, infos.get(server).compatible(), hover);
        if (!hover) return;
        //? if >=1.21.6 {
        g.setTooltipForNextFrame(HINT, mouseX, mouseY);
        //?} else if >=1.20.2 {
        /*ClientCompat.screen().setTooltipForNextRenderPass(HINT);
        *///?} else
        /*if (ClientCompat.screen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen list) list.setToolTip(java.util.List.of(HINT));*/
    }

    /** A click on the server's row: on the chip, opens the details (and the row ignores the click). */
    public static boolean click(ServerData server, double mouseX, double mouseY) {
        TroupeStatus.Info info = infos.get(server);
        if (info == null || !over(spots.get(server), mouseX, mouseY)) return false;
        ClientCompat.setScreen(new ServerInfoScreen(ClientCompat.screen(), server, info));
        return true;
    }
}

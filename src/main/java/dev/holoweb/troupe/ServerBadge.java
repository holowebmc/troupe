package dev.holoweb.troupe;

import com.google.common.collect.MapMaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
*///?}

/**
 * A small "T" on the bottom-right corner of a server's icon in the multiplayer list when its ping says it runs Troupe;
 * hovering it lists the resources and the download size. Red when the server speaks another Troupe protocol.
 */
public final class ServerBadge {
    private static final int SIZE = 9;
    private static final Map<ServerData, TroupeStatus.Info> infos = new MapMaker().weakKeys().makeMap();

    private ServerBadge() {}

    /** From the pinger: the latest status of {@code server} (null info: it doesn't run Troupe, or not anymore). */
    public static void set(ServerData server, TroupeStatus.Info info) {
        if (info == null) infos.remove(server);
        else infos.put(server, info);
    }

    //? if >=26.1 {
    /** {@code x, y}: the row's content corner, where the 32×32 icon starts. */
    public static void draw(GuiGraphicsExtractor g, ServerData server, int x, int y, int mouseX, int mouseY) {
        TroupeStatus.Info info = infos.get(server);
        if (info == null) return;
        Font font = Minecraft.getInstance().font;
        int bx = x + 32 - SIZE, by = y + 32 - SIZE;
        g.fill(bx - 1, by - 1, bx + SIZE + 1, by + SIZE + 1, 0xFF000000);
        g.fill(bx, by, bx + SIZE, by + SIZE, info.compatible() ? 0xFF7A4FD8 : 0xFFB03030);
        g.text(font, "T", bx + (SIZE - font.width("T")) / 2 + 1, by + 1, 0xFFFFFFFF, false);
        if (over(bx, by, mouseX, mouseY)) g.setComponentTooltipForNextFrame(font, tooltip(info), mouseX, mouseY);
    }
    //?} else {
    /*public static void draw(GuiGraphics g, JoinMultiplayerScreen screen, ServerData server, int x, int y, int mouseX, int mouseY) {
        TroupeStatus.Info info = infos.get(server);
        if (info == null) return;
        Font font = Minecraft.getInstance().font;
        int bx = x + 32 - SIZE, by = y + 32 - SIZE;
        g.fill(bx - 1, by - 1, bx + SIZE + 1, by + SIZE + 1, 0xFF000000);
        g.fill(bx, by, bx + SIZE, by + SIZE, info.compatible() ? 0xFF7A4FD8 : 0xFFB03030);
        g.drawString(font, "T", bx + (SIZE - font.width("T")) / 2 + 1, by + 1, 0xFFFFFF, false);
        if (over(bx, by, mouseX, mouseY)) screen.setToolTip(tooltip(info));
    }
    *///?}

    private static boolean over(int bx, int by, int mouseX, int mouseY) {
        return mouseX >= bx - 1 && mouseX < bx + SIZE + 1 && mouseY >= by - 1 && mouseY < by + SIZE + 1;
    }

    private static List<Component> tooltip(TroupeStatus.Info info) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatableWithFallback("troupe.badge.title", "Troupe: %s resources, %s",
                info.resources(), size(info.size())).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (!info.names().isEmpty()) {
            String names = String.join(", ", info.names()) + (info.resources() > info.names().size() ? ", …" : "");
            lines.add(Component.literal(names).withStyle(ChatFormatting.GRAY));
        }
        if (!info.compatible())
            lines.add(Component.translatableWithFallback("troupe.badge.incompatible", "Another Troupe version (server protocol %s, this client %s)",
                    info.protocol(), dev.holoweb.troupe.net.Protocol.VERSION).withStyle(ChatFormatting.RED));
        return lines;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1 << 20) return (bytes >> 10) + " KiB";
        return String.format("%.1f MiB", bytes / 1048576.0);
    }
}

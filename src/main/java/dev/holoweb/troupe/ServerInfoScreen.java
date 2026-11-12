package dev.holoweb.troupe;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * From the server list: what a Troupe server's last ping said (resources, download size), its Troupe version as a chip
 * in the corner, and Forget permission when its scripts were allowed. The resource names sit in an accordion, closed.
 */
final class ServerInfoScreen extends CardScreen {
    private static final int MAX_NAME_LINES = 6;
    private final Screen previous;
    private final ServerData server;
    private final TroupeStatus.Info info;
    private ServerIcon icon;
    private boolean open;

    ServerInfoScreen(Screen previous, ServerData server, TroupeStatus.Info info) {
        super(Component.literal(server.name));
        this.previous = previous;
        this.server = server;
        this.info = info;
    }

    private Component names() {
        return Component.literal(String.join(", ", info.names()) + (info.resources() > info.names().size() ? ", …" : ""));
    }

    @Override
    protected void init() {
        if (icon == null) icon = new ServerIcon(server);
        super.init();
    }

    @Override
    public void removed() {
        if (icon != null) icon.close();
        icon = null;
    }

    @Override
    protected int accent() {
        return info.compatible() ? Panel.PURPLE : Panel.BAD;
    }

    @Override
    protected Identifier icon() {
        return icon.id();
    }

    @Override
    protected int iconSize() {
        return 22;
    }

    @Override
    protected String sub() {
        return server.ip;
    }

    @Override
    protected Panel.Chip corner() {
        return Panel.Chip.status(info.compatible() ? Component.translatableWithFallback("troupe.info.badge.ok", "compatible")
                : Component.translatableWithFallback("troupe.info.badge.bad", "not compatible"), info.compatible(), false);
    }

    @Override
    protected List<Component> cornerTooltip() {
        return List.of(info.compatible()
                ? Component.translatableWithFallback("troupe.info.compatible", "Runs a compatible Troupe")
                : Component.translatableWithFallback("troupe.info.protocol", "Server protocol %s, this client %s",
                        info.protocol(), dev.holoweb.troupe.net.Protocol.VERSION));
    }

    @Override
    protected int bodyHeight(int inner) {
        int h = 2 * Panel.ROW - 4;
        if (!info.names().isEmpty()) h += 10 + Panel.ROW - 4 + (open ? 4 + Panel.lines(font, names(), inner - 10, MAX_NAME_LINES) * 10 : 0);
        return h;
    }

    @Override
    protected void addBodyWidgets(int left, int top, int inner) {
        if (!info.names().isEmpty())
            toggle(left, top + 2 * Panel.ROW - 4 + 10, open, Component.translatableWithFallback("troupe.info.names", "Resource names"), () -> open = !open);
    }

    @Override
    protected void drawBody(Panel.Canvas c, int left, int top, int inner) {
        Panel.pair(c, left, top, inner, Component.translatableWithFallback("troupe.info.resources", "Resources"),
                Component.literal(String.valueOf(info.resources())), Panel.TEXT);
        Panel.pair(c, left, top + Panel.ROW, inner, Component.translatableWithFallback("troupe.info.download", "Download"),
                Component.literal(Panel.size(info.size())), Panel.TEXT);
        if (open) Panel.wrapped(c, names(), left + 10, top + 2 * Panel.ROW - 4 + 10 + Panel.ROW - 4 + 4, inner - 10, MAX_NAME_LINES, Panel.MUTED);
    }

    @Override
    protected void addButtons(int x, int y, int w) {
        if (TroupeClient.trusted(server.ip))
            buttons(x, y, w, Component.translatableWithFallback("troupe.info.forget", "Forget permission"), () -> {
                TroupeClient.forget(server.ip);
                rebuildWidgets();
            }, CommonComponents.GUI_DONE, this::onClose);
        else buttons(x, y, w, CommonComponents.GUI_DONE, this::onClose, null, null);
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(previous);
    }
}

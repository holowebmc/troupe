package dev.holoweb.troupe;

import dev.holoweb.troupe.net.Protocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Joining a Troupe server, in one window. The first time on a server it asks first ("This server runs scripts", with
 * what they can and can't do in the tooltip of a "?" in the corner): nothing is downloaded before Join. Then, and on
 * servers already allowed, it shows the download and asset loading until the player gets in.
 * {@code progress} is 0..1, or negative while there's nothing to measure.
 */
final class JoinScreen extends CardScreen {
    private static final Component LOADING = Component.translatableWithFallback("troupe.loading.title", "Loading the server's resources");
    private static final Component ASK = Component.translatableWithFallback("troupe.trust.title", "This server runs scripts");
    private static final Component INTRO = Component.translatableWithFallback("troupe.trust.intro",
            "Joining downloads its resources, and their scripts run in your game.");
    private static final List<Component> HELP = List.of(
            Component.translatableWithFallback("troupe.trust.details", "What can these scripts do?").withStyle(ChatFormatting.WHITE),
            can("troupe.trust.can.draw", "Draw on your screen and in the world"),
            can("troupe.trust.can.camera", "Move the camera and add visual effects"),
            can("troupe.trust.can.pages", "Open interface pages"),
            Component.literal("✖ ").withStyle(ChatFormatting.RED).append(
                    Component.translatableWithFallback("troupe.trust.cannot.files", "They can't read or change your files").withStyle(ChatFormatting.GRAY)));

    final Screen previous;
    final Runnable cancel;
    private final Supplier<Component> status;
    private final DoubleSupplier progress;
    private final Runnable onJoin;
    /** Still asking: what the server's manifest announced (null once loading). */
    private Protocol.Manifest asking;
    /** Mods of this client the server's scripts will reach. */
    private final List<String> mods;
    private ServerIcon icon;

    /**
     * @param asking the server's manifest when the player must be asked first, else null (load right away)
     * @param onJoin when the player says Join (lets the held download go)
     */
    JoinScreen(Screen previous, Protocol.Manifest asking, Supplier<Component> status, DoubleSupplier progress, Runnable cancel, Runnable onJoin) {
        super(asking != null ? ASK : LOADING);
        this.previous = previous;
        this.asking = asking;
        this.mods = asking == null ? List.of() : TroupeClient.installedModules(asking.modules());
        this.status = status;
        this.progress = progress;
        this.cancel = cancel;
        this.onJoin = onJoin;
    }

    private static Component can(String key, String fallback) {
        return Component.literal("✔ ").withStyle(ChatFormatting.GREEN).append(Component.translatableWithFallback(key, fallback).withStyle(ChatFormatting.GRAY));
    }

    private void join() {
        TroupeClient.trustCurrent(mods);
        asking = null;
        rebuildWidgets();
        onJoin.run();
    }

    @Override
    protected void init() {
        if (icon == null) icon = new ServerIcon(TroupeClient.server());
        super.init();
    }

    @Override
    public void removed() {
        if (icon != null) icon.close();
        icon = null;
    }

    @Override
    protected Component heading() {
        return asking != null ? ASK : LOADING;
    }

    @Override
    protected int accent() {
        return asking != null ? Panel.AMBER : Panel.BLUE;
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
        return Panel.serverLine(null);
    }

    @Override
    protected Panel.Chip corner() {
        return asking != null ? new Panel.Chip(Component.literal("?")) : null;
    }

    @Override
    protected List<Component> cornerTooltip() {
        if (mods.isEmpty()) return HELP;
        List<Component> all = new java.util.ArrayList<>(HELP);
        all.add(Component.translatableWithFallback("troupe.trust.mods.title", "Through mods you have installed:").withStyle(ChatFormatting.WHITE));
        for (String module : mods) all.add(Component.literal("➜ ").withStyle(ChatFormatting.GOLD).append(TroupeClient.moduleNote(module).copy().withStyle(ChatFormatting.GRAY)));
        return all;
    }

    private Component announced() {
        long size = asking.blobs().stream().mapToLong(Protocol.Blob::size).sum();
        return Component.translatableWithFallback("troupe.trust.announced", "%s resources · %s", asking.resources().size(), Panel.size(size));
    }

    @Override
    protected int bodyHeight(int inner) {
        if (asking != null) return Panel.lines(font, INTRO, inner, 3) * 10 + 4 + 9 + (mods.isEmpty() ? 0 : 11);
        return 9 + 6 + 3;
    }

    @Override
    protected void addButtons(int x, int y, int w) {
        if (asking != null)
            buttons(x, y, w, Component.translatableWithFallback("troupe.trust.join", "Join"), this::join,
                    Component.translatableWithFallback("troupe.trust.leave", "Disconnect"), cancel);
        else buttons(x, y, w, CommonComponents.GUI_CANCEL, cancel, null, null);
    }

    @Override
    protected void drawBody(Panel.Canvas c, int left, int top, int inner) {
        if (asking != null) {
            int h = Panel.wrapped(c, INTRO, left, top, inner, 3, Panel.TEXT);
            c.text(announced(), left, top + h + 4, Panel.MUTED);
            if (!mods.isEmpty()) {
                String line = Component.translatableWithFallback("troupe.trust.mods", "They also use your mods: %s", String.join(", ", mods)).getString();
                c.text(Component.literal(c.font().plainSubstrByWidth(line, inner)), left, top + h + 4 + 11, Panel.AMBER);
            }
            return;
        }
        double p = progress.getAsDouble();
        Component percent = p >= 0 ? Component.literal((int) (Math.min(1, p) * 100) + "%") : null;
        int room = inner - (percent == null ? 0 : c.font().width(percent) + 8);
        c.text(Component.literal(c.font().plainSubstrByWidth(status.get().getString(), room)), left, top, Panel.MUTED);
        if (percent != null) c.text(percent, left + inner - c.font().width(percent), top, Panel.TEXT);
        Panel.bar(c, left, top + 15, inner, p, accent());
    }

    /** Esc says Disconnect while asking; a download only stops with Cancel. */
    @Override
    public boolean shouldCloseOnEsc() {
        return asking != null;
    }

    @Override
    public void onClose() {
        cancel.run();
    }

    @Override
    protected boolean dirt() {
        return true;
    }
}

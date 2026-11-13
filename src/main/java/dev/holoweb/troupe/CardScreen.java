package dev.holoweb.troupe;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A Troupe window: a centered card (icon, title, a muted line, divider, body) with its buttons under it. The card is
 * drawn before the widgets, so a body can hold widgets too (an accordion toggle).
 */
abstract class CardScreen extends Screen {
    static final int GAP = 8, BUTTON = 20, TOP = 22 + 4 + 1 + 7;
    protected int x, y, w, h;
    /** The screen this one opened over. */
    private final Screen under = ClientCompat.screen();

    protected CardScreen(Component title) {
        super(title);
    }

    /**
     * While configuring, the game's connect screen ticks the connection (and ends it on a disconnect) only while it is
     * the open screen: covering it, a Troupe window keeps ticking it, or Cancel / Disconnect would never take effect.
     */
    @Override
    public void tick() {
        super.tick();
        if (under instanceof net.minecraft.client.gui.screens.ConnectScreen || under instanceof CardScreen) under.tick();
    }

    protected abstract int accent();

    /** The title on the card (the screen's own, unless it changes while open). */
    protected Component heading() {
        return title;
    }

    protected abstract Identifier icon();

    /** Pixel size of {@link #icon()} (16 for item textures, 22 for a server's icon). */
    protected int iconSize() {
        return 16;
    }

    /** A chip in the card's top-right corner (null for none), and what hovering it shows (null: nothing). */
    protected Panel.Chip corner() {
        return null;
    }

    protected java.util.List<Component> cornerTooltip() {
        return null;
    }

    private int[] cornerSpot() {
        Panel.Chip chip = corner();
        if (chip == null) return null;
        int cw = chip.width(font), ch = chip.height();
        return new int[]{x + w - Panel.PAD - cw, y + Panel.PAD + 6 - ch / 2, cw, ch}; // centered on the title line
    }

    /** The corner chip's tooltip when the mouse is on it. */
    private java.util.List<Component> tooltipAt(int mouseX, int mouseY) {
        int[] s = cornerSpot();
        if (s == null || mouseX < s[0] || mouseX >= s[0] + s[2] || mouseY < s[1] || mouseY >= s[1] + s[3]) return null;
        return cornerTooltip();
    }

    /** The muted line under the title (null for none). */
    protected abstract String sub();

    /** Height of the body for an inner width of {@code inner}. */
    protected abstract int bodyHeight(int inner);

    protected abstract void drawBody(Panel.Canvas c, int left, int top, int inner);

    protected void addBodyWidgets(int left, int top, int inner) {}

    /** The buttons under the card, {@code w} wide starting at {@code x}. */
    protected abstract void addButtons(int x, int y, int w);

    /** Dirt behind the card instead of the blurred / darkened game (screens shown while joining, before 26.x). */
    protected boolean dirt() {
        return false;
    }

    @Override
    protected void init() {
        w = Math.min(Panel.WIDTH, width - 20);
        int inner = w - 2 * Panel.PAD;
        h = Panel.PAD + TOP + bodyHeight(inner) + Panel.PAD;
        x = (width - w) / 2;
        y = Math.max(4, (height - h - GAP - BUTTON) / 2);
        addBodyWidgets(x + Panel.PAD, y + Panel.PAD + TOP, inner);
        addButtons(x, y + h + GAP, w);
    }

    /** Two buttons side by side, or one centered when {@code second} is null. */
    protected void buttons(int x, int y, int w, Component first, Runnable a, Component second, Runnable b) {
        if (second == null) {
            int bw = Math.min(w, 140);
            addRenderableWidget(Button.builder(first, btn -> a.run()).bounds(x + (w - bw) / 2, y, bw, BUTTON).build());
            return;
        }
        int half = (w - GAP) / 2;
        addRenderableWidget(Button.builder(first, btn -> a.run()).bounds(x, y, half, BUTTON).build());
        addRenderableWidget(Button.builder(second, btn -> b.run()).bounds(x + w - half, y, half, BUTTON).build());
    }

    /** An accordion header: "▸ label" / "▾ label", toggling {@code onToggle} (which rebuilds the window). */
    protected void toggle(int x, int y, boolean open, Component label, Runnable onToggle) {
        Component text = Component.literal(open ? "▾ " : "▸ ").append(label);
        addRenderableWidget(new PlainTextButton(x, y, font.width(text), 10, text, b -> {
            onToggle.run();
            rebuildWidgets();
        }, font));
    }

    private void drawCard(Panel.Canvas c) {
        Panel.card(c, x, y, w, h, accent());
        int left = x + Panel.PAD, inner = w - 2 * Panel.PAD, at = y + Panel.PAD;
        int[] s = cornerSpot();
        Panel.header(c, left, at, s == null ? inner : s[0] - 6 - left, icon(), iconSize(), heading(), sub());
        if (s != null) corner().draw(c, s[0], s[1]);
        Panel.divider(c, left, at + 26, inner);
        drawBody(c, left, at + TOP, inner);
    }

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        drawCard(Panel.canvas(g, font));
        var tip = tooltipAt(mouseX, mouseY);
        if (tip != null) g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
    }
    //?} else if >=1.21.6 {
    /*@Override
    public void renderBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        drawCard(Panel.canvas(g, font));
        var tip = tooltipAt(mouseX, mouseY);
        if (tip != null) g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
    }
    *///?} else if >=1.20.2 {
    /*/^* The screen draws its background itself, then the widgets: the tooltip waits for those. *^/
    @Override
    public void renderBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        drawCard(Panel.canvas(g, font));
    }

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        var tip = tooltipAt(mouseX, mouseY);
        if (tip != null) g.renderComponentTooltip(font, tip, mouseX, mouseY);
    }
    *///?} else {
    /*@Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (dirt()) renderDirtBackground(g);
        else renderBackground(g);
        drawCard(Panel.canvas(g, font));
        super.render(g, mouseX, mouseY, delta);
        var tip = tooltipAt(mouseX, mouseY);
        if (tip != null) g.renderComponentTooltip(font, tip, mouseX, mouseY);
    }
    *///?}
}

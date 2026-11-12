package dev.holoweb.troupe;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A yes / no question in a Troupe card (Esc says no). */
final class CardConfirm extends CardScreen {
    private static final int MAX_LINES = 6;
    private final Component message, yes, no;
    private final Identifier icon;
    private final BooleanConsumer answer;

    CardConfirm(Identifier icon, Component title, Component message, Component yes, Component no, BooleanConsumer answer) {
        super(title);
        this.icon = icon;
        this.message = message;
        this.yes = yes;
        this.no = no;
        this.answer = answer;
    }

    @Override
    protected int accent() {
        return Panel.BLUE;
    }

    @Override
    protected Identifier icon() {
        return icon;
    }

    @Override
    protected String sub() {
        return Panel.serverLine(null);
    }

    @Override
    protected int bodyHeight(int inner) {
        return Panel.lines(font, message, inner, MAX_LINES) * 10;
    }

    @Override
    protected void drawBody(Panel.Canvas c, int left, int top, int inner) {
        Panel.wrapped(c, message, left, top, inner, MAX_LINES, Panel.TEXT);
    }

    @Override
    protected void addButtons(int x, int y, int w) {
        buttons(x, y, w, yes, () -> answer.accept(true), no, () -> answer.accept(false));
    }

    @Override
    public void onClose() {
        answer.accept(false);
    }

    @Override
    protected boolean dirt() {
        return true;
    }
}

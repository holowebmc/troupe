package dev.holoweb.troupe;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else
/*import net.minecraft.client.gui.GuiGraphics;*/

/**
 * Shown while the server's resources download and their assets load, before the player gets in.
 * {@code progress} is 0..1, or negative while there's nothing to measure (loading assets).
 */
final class LoadingScreen extends Screen {
    final Screen previous;
    private final Supplier<Component> status;
    private final DoubleSupplier progress;
    private final Runnable cancel;

    LoadingScreen(Screen previous, Supplier<Component> status, DoubleSupplier progress, Runnable cancel) {
        super(Component.translatableWithFallback("troupe.loading.title", "Loading the server's resources"));
        this.previous = previous;
        this.status = status;
        this.progress = progress;
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancel.run())
                .bounds(width / 2 - 100, height / 2 + 36, 200, 20).build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    //? if >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title.getString(), width / 2, height / 2 - 30, 0xFFFFFFFF);
        g.centeredText(font, status.get().getString(), width / 2, height / 2 - 12, 0xFFAAAAAA);
        bar(g::fill);
    }
    //?} else {
    /*@Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderDirtBackground(g);
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(font, title, width / 2, height / 2 - 30, 0xFFFFFF);
        g.drawCenteredString(font, status.get(), width / 2, height / 2 - 12, 0xAAAAAA);
        bar(g::fill);
    }
    *///?}

    private interface Fill {
        void fill(int x0, int y0, int x1, int y1, int argb);
    }

    /** A thin bar; while unmeasured, a block sweeping across. */
    private void bar(Fill fill) {
        int x0 = width / 2 - 100, x1 = width / 2 + 100, y = height / 2 + 6;
        fill.fill(x0, y, x1, y + 4, 0xFF404040);
        double p = progress.getAsDouble();
        if (p >= 0) {
            fill.fill(x0, y, x0 + (int) (200 * Math.min(1, p)), y + 4, 0xFFFFFFFF);
        } else {
            int at = (int) (System.currentTimeMillis() / 8 % 240) - 40;
            fill.fill(Math.max(x0, x0 + at), y, Math.min(x1, x0 + at + 40), y + 4, 0xFFFFFFFF);
        }
    }
}

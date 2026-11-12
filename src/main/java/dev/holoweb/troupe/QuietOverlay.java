package dev.holoweb.troupe;

import dev.holoweb.troupe.mixin.LoadingOverlayAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.resources.ReloadInstance;

import java.util.Optional;
import java.util.function.Consumer;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else
/*import net.minecraft.client.gui.GuiGraphics;*/

/**
 * A resource reload without the Mojang screen: when Troupe's asset packs go on or off (joining, leaving), the open
 * window (the join window, the server list) stays on screen while the reload runs, and the reload ends as vanilla's
 * would. Still a {@link LoadingOverlay}, so the game keeps queueing other reloads behind it.
 */
public final class QuietOverlay extends LoadingOverlay {
    private static volatile long until;
    private final ReloadInstance reload;
    private final Consumer<Optional<Throwable>> onFinish;
    private boolean finished;

    private QuietOverlay(ReloadInstance reload, Consumer<Optional<Throwable>> onFinish) {
        super(Minecraft.getInstance(), reload, onFinish, true);
        this.reload = reload;
        this.onFinish = onFinish;
    }

    /** The next reload in the coming 30 s is Troupe's: keep it quiet. */
    public static void request() {
        until = System.currentTimeMillis() + 30_000;
    }

    /** From the mixin on the game's setOverlay: swaps the reload overlay when a quiet one was asked for. */
    public static Overlay wrap(Overlay overlay) {
        if (!(overlay instanceof LoadingOverlay) || overlay instanceof QuietOverlay || System.currentTimeMillis() >= until
                || ClientCompat.screen() == null) return overlay; // no window to keep showing (in the world): vanilla's
        until = 0;
        LoadingOverlayAccess a = (LoadingOverlayAccess) overlay;
        return new QuietOverlay(a.troupe$reload(), a.troupe$onFinish());
    }

    /** As vanilla's overlay does once the reload is done: report, leave, and lay the window out again (fonts may change). */
    private void check() {
        if (finished || !reload.isDone()) return;
        finished = true;
        try {
            reload.checkExceptions();
            onFinish.accept(Optional.empty());
        } catch (Throwable t) {
            onFinish.accept(Optional.of(t));
        }
        Minecraft mc = Minecraft.getInstance();
        //? if >=26.1 {
        mc.gui.setOverlay(null);
        //?} else
        /*mc.setOverlay(null);*/
        Screen screen = ClientCompat.screen();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        //? if >=26.1 {
        if (screen != null) screen.init(w, h);
        //?} else
        /*if (screen != null) screen.init(mc, w, h);*/
    }

    //? if >=26.1 {
    /** In the tick, as vanilla's: ending the reload while a frame is being extracted leaves textures behind. */
    @Override
    public void tick() {
        check();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Screen screen = ClientCompat.screen();
        if (screen != null) screen.extractRenderStateWithTooltipAndSubtitles(g, mouseX, mouseY, delta);
    }
    //?} else {
    /*@Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        Screen screen = ClientCompat.screen();
        if (screen != null) screen.renderWithTooltip(g, mouseX, mouseY, delta);
        check();
    }
    *///?}
}

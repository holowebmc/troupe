package dev.holoweb.troupe.lua;

import java.util.List;
import java.util.Map;

/** Where client pages are drawn (Vitrine in the game, a fake in tests). Render thread only. */
public interface UiBackend {
    interface View {
        /** To the page: {@code addEventListener('message', e => e.data)}. */
        void send(String json);

        /** Answers the page's {@code fetch(POST)} to {@code name}. */
        void on(String name, PageHandler handler);

        void visible(boolean visible);

        /** Screens only: mouse and keyboard to the page (Esc gives them back). */
        void focus(boolean focus);

        /** Screens only: false once Esc (or the game) took the mouse and keyboard back. */
        boolean focused();

        /** Screens only: 0..1 on top of the game's volume. Panels take theirs in {@link #place}. */
        void volume(float volume);

        /**
         * World panels only: centred at (x, y, z), {@code width} blocks wide, facing whoever looks with
         * {@code yaw} / {@code pitch}; hidden beyond {@code distance} blocks, heard up to {@code range} blocks.
         */
        void place(double x, double y, double z, float yaw, float pitch, float width, boolean billboard, float distance, float volume, float range);

        void close();
    }

    @FunctionalInterface
    interface PageHandler {
        void handle(String body, PageReply reply);
    }

    interface PageReply {
        void ok(String json);

        void error(String message);
    }

    /** What the slots of a resource's pages show: {@code <div style="--vitrine-bind: troupe:slot" data-vitrine="key 30 10">}. */
    @FunctionalInterface
    interface Slots {
        /** The things in slot {@code key} (see {@code UiApi}), now; {@code args} is what follows the key in {@code data-vitrine}. */
        List<Map<String, Object>> slot(String key, int width, int height, List<String> args);
    }

    /** Fills the slots of {@code resource}'s pages from {@code slots}; null stops. Nothing is drawn before Minecraft 26.2. */
    void slots(String resource, Slots slots);

    /** Serves {@code page}s of {@code resource} from {@code files} (paths inside {@code ui/}); null stops. */
    void serve(String resource, LuaRuntime.Files files);

    View screen(String resource, String page);

    /** A panel of {@code pxWidth} x {@code pxHeight} page pixels; {@link View#place} is called right after. */
    View world(String resource, String page, int pxWidth, int pxHeight);
}

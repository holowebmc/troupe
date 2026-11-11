package dev.holoweb.troupe.lua;

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

        /** World panels only. */
        void move(double x, double y, double z, float yaw, float width);

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

    /** Serves {@code page}s of {@code resource} from {@code files} (paths inside {@code ui/}); null stops. */
    void serve(String resource, LuaRuntime.Files files);

    View screen(String resource, String page);

    /** A panel centred at (x, y, z), {@code width} blocks wide, facing whoever looks with {@code yaw}. */
    View world(String resource, String page, int pxWidth, int pxHeight, double x, double y, double z, float yaw, float width, boolean billboard);
}

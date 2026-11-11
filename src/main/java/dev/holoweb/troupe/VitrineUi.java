package dev.holoweb.troupe;

import dev.holoweb.troupe.lua.LuaRuntime;
import dev.holoweb.troupe.lua.UiBackend;
import dev.holoweb.vitrine.ScreenView;
import dev.holoweb.vitrine.Vitrine;
import dev.holoweb.vitrine.WorldView;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Resource pages through Vitrine: {@code vitrine://troupe.<resource>/<page>}. Only loaded when Vitrine is installed
 * (see {@link #create}), so the client works without it.
 */
final class VitrineUi implements UiBackend {
    private VitrineUi() {}

    static UiBackend create() {
        return new VitrineUi();
    }

    private static String host(String resource) {
        return "troupe." + resource;
    }

    @Override
    public void serve(String resource, LuaRuntime.Files files) {
        Vitrine.serve(host(resource), files == null ? null : files::read);
    }

    @Override
    public View screen(String resource, String page) {
        ScreenView v = Vitrine.screen("vitrine://" + host(resource) + "/" + page);
        return new View() {
            public void send(String json) {
                v.send(json);
            }

            public void on(String name, PageHandler handler) {
                v.on(name, (body, reply) -> handler.handle(body, reply(reply)));
            }

            public void visible(boolean visible) {
                v.visible(visible);
            }

            public void focus(boolean focus) {
                v.focus(focus);
            }

            public void move(double x, double y, double z, float yaw, float width) {}

            public void close() {
                v.close();
            }
        };
    }

    @Override
    public View world(String resource, String page, int pxWidth, int pxHeight, double x, double y, double z, float yaw, float width, boolean billboard) {
        WorldView v = Vitrine.world("vitrine://" + host(resource) + "/" + page, pxWidth, pxHeight, new Vec3(x, y, z), rotation(yaw), width);
        v.billboard(billboard);
        return new View() {
            public void send(String json) {
                v.send(json);
            }

            public void on(String name, PageHandler handler) {
                v.on(name, (body, reply) -> handler.handle(body, reply(reply)));
            }

            public void visible(boolean visible) {
                v.visible(visible);
            }

            public void focus(boolean focus) {}

            public void move(double x, double y, double z, float yaw, float width) {
                v.move(new Vec3(x, y, z), rotation(yaw), width);
            }

            public void close() {
                v.close();
            }
        };
    }

    /** Readable by whoever looks with {@code yaw} (Vitrine draws the page facing +Z of the rotation). */
    private static Quaternionf rotation(float yaw) {
        return new Quaternionf().rotateY((float) Math.toRadians(180 - yaw));
    }

    private static PageReply reply(Vitrine.Reply reply) {
        return new PageReply() {
            public void ok(String json) {
                reply.ok(json);
            }

            public void error(String message) {
                reply.error(message);
            }
        };
    }
}

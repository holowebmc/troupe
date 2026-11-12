package dev.holoweb.troupe;

import dev.holoweb.troupe.lua.LuaRuntime;
import dev.holoweb.troupe.lua.UiBackend;
import dev.holoweb.vitrine.ScreenView;
import dev.holoweb.vitrine.Vitrine;
import dev.holoweb.vitrine.WorldView;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resource pages through Vitrine: {@code vitrine://troupe.<resource>/<page>}. Only loaded when Vitrine is installed
 * (see {@link #create}), so the client works without it.
 */
final class VitrineUi implements UiBackend {
    /** Page host → what its slots show. */
    private final Map<String, Slots> slots = new HashMap<>();
    private long complained;

    private VitrineUi() {}

    static UiBackend create() {
        VitrineUi ui = new VitrineUi();
        //? if >=26.2
        Vitrine.bind("troupe:slot", ui::slot);
        return ui;
    }

    @Override
    public void slots(String resource, Slots source) {
        if (source == null) slots.remove(host(resource));
        else slots.put(host(resource), source);
    }

    //? if >=26.2 {
    /** One {@code --vitrine-bind: troupe:slot}: {@code data-vitrine="key [yaw [pitch]] ..."}, of the resource whose page it is in. */
    private void slot(Vitrine.Slot slot, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector nodes) {
        try { // Vitrine removes a bind that throws, and this one is every resource's
            String page = slot.page();
            if (!page.startsWith("vitrine://")) return;
            int end = page.indexOf('/', 10);
            Slots source = slots.get(end < 0 ? page.substring(10) : page.substring(10, end));
            List<String> data = List.of(slot.data().trim().split(" +"));
            if (source == null || data.get(0).isEmpty()) return;
            List<String> args = data.subList(1, data.size());
            SlotDraw.draw(source.slot(data.get(0), slot.width(), slot.height(), args), slot.width(), slot.height(), angle(args, 0), angle(args, 1), pose, nodes);
        } catch (RuntimeException e) {
            if (System.nanoTime() - complained < 5_000_000_000L) return;
            complained = System.nanoTime();
            Troupe.LOG.warn("Slot '{}' of {} failed", slot.data(), slot.page(), e);
        }
    }

    private static float angle(List<String> args, int i) {
        try {
            float f = i < args.size() ? Float.parseFloat(args.get(i)) : 0;
            return Float.isFinite(f) ? f : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    //?}

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

            public boolean focused() {
                return v.isFocused();
            }

            public void volume(float volume) {
                v.volume(volume);
            }

            public void place(double x, double y, double z, float yaw, float pitch, float width, boolean billboard, float distance, float volume, float range) {}

            public void close() {
                v.close();
            }
        };
    }

    @Override
    public View world(String resource, String page, int pxWidth, int pxHeight) {
        WorldView v = Vitrine.world("vitrine://" + host(resource) + "/" + page, pxWidth, pxHeight, Vec3.ZERO, new Quaternionf(), 1);
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

            public boolean focused() {
                return false;
            }

            public void volume(float volume) {}

            public void place(double x, double y, double z, float yaw, float pitch, float width, boolean billboard, float distance, float volume, float range) {
                // Readable by whoever looks with yaw / pitch (Vitrine draws the page facing +Z of the rotation).
                v.move(new Vec3(x, y, z), new Quaternionf().rotateY((float) Math.toRadians(180 - yaw)).rotateX((float) Math.toRadians(-pitch)), width);
                v.billboard(billboard);
                v.maxDistance(distance);
                v.audio(volume, range);
            }

            public void close() {
                v.close();
            }
        };
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

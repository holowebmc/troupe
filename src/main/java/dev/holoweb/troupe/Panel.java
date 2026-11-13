package dev.holoweb.troupe;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** The look shared by Troupe's windows ({@link CardScreen}): a dark card with an accent stripe. */
final class Panel {
    static final int WIDTH = 264, PAD = 12, ROW = 13;
    static final int TEXT = 0xFFF2F2F2, MUTED = 0xFF9AA0A8, FAINT = 0xFF6C727A, GOOD = 0xFF5CCB6B, BAD = 0xFFE5625A;
    static final int AMBER = 0xFFF2B33D, BLUE = 0xFF5AA9F0, PURPLE = 0xFF9B7BF0;
    private static final int BACK = 0xE6121418, EDGE = 0x22FFFFFF, LINE = 0x1FFFFFFF, TRACK = 0x26FFFFFF;

    /** What a window draws with, the same on every version. */
    interface Canvas {
        void fill(int x0, int y0, int x1, int y1, int argb);

        void text(FormattedCharSequence text, int x, int y, int argb);

        default void text(Component text, int x, int y, int argb) {
            text(text.getVisualOrderText(), x, y, argb);
        }

        /** Text drawn at {@code scale} from (x, y). */
        void text(Component text, float x, float y, int argb, float scale);

        /** A whole texture, drawn {@code size} × {@code size}. */
        void icon(Identifier texture, int x, int y, int size);

        Font font();
    }

    static Canvas canvas(GuiGraphicsExtractor g, Font font) {
        return new Canvas() {
            public void fill(int x0, int y0, int x1, int y1, int argb) {
                g.fill(x0, y0, x1, y1, argb);
            }

            public void text(FormattedCharSequence text, int x, int y, int argb) {
                //? if >=26.1 {
                g.text(font, text, x, y, argb, false);
                //?} else
                /*g.drawString(font, text, x, y, argb, false);*/
            }

            public void text(Component text, float x, float y, int argb, float scale) {
                //? if >=1.21.6 {
                g.pose().pushMatrix();
                g.pose().translate(x, y);
                g.pose().scale(scale, scale);
                text(text, 0, 0, argb);
                g.pose().popMatrix();
                //?} else {
                /*g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(scale, scale, 1);
                text(text, 0, 0, argb);
                g.pose().popPose();
                *///?}
            }

            public void icon(Identifier texture, int x, int y, int size) {
                //? if >=1.21.6 {
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, size, size);
                //?} else
                /*g.blit(texture, x, y, 0, 0, size, size, size, size);*/
            }

            public Font font() {
                return font;
            }
        };
    }

    /** Card at (x, y): background, hairline edge, accent stripe on top. */
    static void card(Canvas c, int x, int y, int w, int h, int accent) {
        c.fill(x, y, x + w, y + h, BACK);
        c.fill(x, y, x + w, y + 1, EDGE);
        c.fill(x, y + h - 1, x + w, y + h, EDGE);
        c.fill(x, y, x + 1, y + h, EDGE);
        c.fill(x + w - 1, y, x + w, y + h, EDGE);
        c.fill(x, y, x + w, y + 2, accent);
    }

    /** Icon ({@code size} px, centered on 22), title and a muted line under it; 22 px tall. */
    static void header(Canvas c, int x, int y, int w, Identifier icon, int size, Component title, String sub) {
        c.icon(icon, x + (22 - size) / 2, y + (22 - size) / 2, size);
        c.text(Component.literal(c.font().plainSubstrByWidth(title.getString(), w - 28)), x + 28, y + 2, TEXT);
        if (sub != null) c.text(Component.literal(c.font().plainSubstrByWidth(sub, w - 28)), x + 28, y + 13, MUTED);
    }

    /** A label on a colored pill; {@code small} draws the text at 3/4 size in a 10 px pill. */
    record Chip(Component label, int back, int fore, boolean small) {
        Chip(Component label) {
            this(label, 0xFF3A3F47, 0xFFFFFFFF, false);
        }

        /** A small status chip: ✔ on solid green, or ✖ on solid red, white text; lighter while hovered. */
        static Chip status(Component label, boolean ok, boolean hover) {
            int back = ok ? (hover ? 0xFF35A957 : 0xFF2B8C47) : (hover ? 0xFFD2463C : 0xFFB8362D);
            return new Chip(Component.literal(ok ? "✔ " : "✖ ").append(label), back, 0xFFFFFFFF, true);
        }

        private float scale() {
            return small ? 0.75f : 1;
        }

        int width(Font font) {
            return Math.round(font.width(label) * scale()) + 8;
        }

        int height() {
            return small ? 10 : 11;
        }

        /** Caps sit centered: 7 px tall at full size. */
        void draw(Canvas c, int x, int y) {
            c.fill(x, y, x + width(c.font()), y + height(), back);
            c.text(label, x + 4, y + (height() - 7 * scale()) / 2, fore, scale());
        }
    }

    static void divider(Canvas c, int x, int y, int w) {
        c.fill(x, y, x + w, y + 1, LINE);
    }

    /** {@code text} wrapped to {@code w}, at most {@code max} lines; returns the height used. */
    static int wrapped(Canvas c, Component text, int x, int y, int w, int max, int argb) {
        var lines = c.font().split(text, w);
        int n = Math.min(max, lines.size());
        for (int i = 0; i < n; i++) c.text(lines.get(i), x, y + i * 10, argb);
        return n * 10;
    }

    /** Lines {@code text} takes wrapped to {@code w} (at most {@code max}). */
    static int lines(Font font, Component text, int w, int max) {
        return Math.min(max, font.split(text, w).size());
    }

    /** A label on the left, its value on the right. */
    static void pair(Canvas c, int x, int y, int w, Component label, Component value, int valueColor) {
        c.text(label, x, y, MUTED);
        c.text(value, x + w - c.font().width(value), y, valueColor);
    }

    /** A thin bar; {@code progress} 0..1, or negative for a block sweeping across. */
    static void bar(Canvas c, int x, int y, int w, double progress, int accent) {
        c.fill(x, y, x + w, y + 3, TRACK);
        if (progress >= 0) {
            c.fill(x, y, x + (int) (w * Math.min(1, progress)), y + 3, accent);
        } else {
            int span = w / 5, pos = (int) (System.currentTimeMillis() / 6 % (w + span)) - span;
            c.fill(x + Math.max(0, pos), y, x + Math.min(w, pos + span), y + 3, accent);
        }
    }

    /** "Name · detail" for the server being joined; either part alone when the other is missing. */
    static String serverLine(String detail) {
        var server = TroupeClient.server();
        String name = server == null || server.name.isBlank() ? null : server.name;
        return name == null ? detail : detail == null ? name : name + "  ·  " + detail;
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1 << 20) return (bytes >> 10) + " KiB";
        return String.format("%.1f MiB", bytes / 1048576.0);
    }
}

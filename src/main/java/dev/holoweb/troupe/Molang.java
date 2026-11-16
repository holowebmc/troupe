package dev.holoweb.troupe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The part of Molang keyframes use for motion: numbers, {@code + - * /}, parentheses, the animation's time
 * ({@code q.anim_time}, {@code q.life_time}, with or without {@code query.}), {@code math.pi} and the math functions
 * below (angles in degrees, as in Molang). Any other variable reads 0; anything that doesn't parse is 0 as a whole.
 */
final class Molang {
    /** An expression of the animation's time, in seconds. */
    @FunctionalInterface
    interface Expr {
        float eval(float time);
    }

    static final Expr ZERO = t -> 0;

    private final String s;
    private int i;

    private Molang(String s) {
        this.s = s;
    }

    /** A plain number becomes a constant; an expression is compiled once. */
    static Expr parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("return ")) t = t.substring(7);
        while (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty()) return ZERO;
        try {
            float f = Float.parseFloat(t);
            return x -> f;
        } catch (NumberFormatException ignored) {
            // an expression
        }
        try {
            Molang p = new Molang(t);
            Expr e = p.sum();
            p.skip();
            return p.i == t.length() ? e : ZERO;
        } catch (RuntimeException e) {
            return ZERO;
        }
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean eat(char c) {
        skip();
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private Expr sum() {
        Expr e = product();
        while (true) {
            Expr l = e;
            if (eat('+')) {
                Expr r = product();
                e = t -> l.eval(t) + r.eval(t);
            } else if (eat('-')) {
                Expr r = product();
                e = t -> l.eval(t) - r.eval(t);
            } else {
                return e;
            }
        }
    }

    private Expr product() {
        Expr e = unary();
        while (true) {
            Expr l = e;
            if (eat('*')) {
                Expr r = unary();
                e = t -> l.eval(t) * r.eval(t);
            } else if (eat('/')) {
                Expr r = unary();
                e = t -> {
                    float d = r.eval(t);
                    return d == 0 ? 0 : l.eval(t) / d;
                };
            } else {
                return e;
            }
        }
    }

    private Expr unary() {
        if (eat('-')) {
            Expr e = unary();
            return t -> -e.eval(t);
        }
        if (eat('+')) return unary();
        return primary();
    }

    private Expr primary() {
        if (eat('(')) {
            Expr e = sum();
            if (!eat(')')) throw new IllegalArgumentException("missing )");
            return e;
        }
        skip();
        int start = i;
        if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            float f = Float.parseFloat(s.substring(start, i));
            return t -> f;
        }
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
        String name = s.substring(start, i);
        if (name.isEmpty()) throw new IllegalArgumentException("expected a value");
        if (eat('(')) {
            List<Expr> args = new ArrayList<>();
            if (!eat(')')) {
                do args.add(sum()); while (eat(','));
                if (!eat(')')) throw new IllegalArgumentException("missing )");
            }
            return call(name, args);
        }
        return switch (name) {
            case "q.anim_time", "query.anim_time", "q.life_time", "query.life_time" -> t -> t;
            case "math.pi" -> t -> (float) Math.PI;
            default -> ZERO; // variables, other queries
        };
    }

    private static Expr call(String name, List<Expr> a) {
        Expr x = a.isEmpty() ? ZERO : a.get(0), y = a.size() < 2 ? ZERO : a.get(1), z = a.size() < 3 ? ZERO : a.get(2);
        return switch (name) {
            case "math.sin" -> t -> (float) Math.sin(Math.toRadians(x.eval(t)));
            case "math.cos" -> t -> (float) Math.cos(Math.toRadians(x.eval(t)));
            case "math.abs" -> t -> Math.abs(x.eval(t));
            case "math.sqrt" -> t -> (float) Math.sqrt(Math.max(0, x.eval(t)));
            case "math.floor" -> t -> (float) Math.floor(x.eval(t));
            case "math.ceil" -> t -> (float) Math.ceil(x.eval(t));
            case "math.round" -> t -> Math.round(x.eval(t));
            case "math.min" -> t -> Math.min(x.eval(t), y.eval(t));
            case "math.max" -> t -> Math.max(x.eval(t), y.eval(t));
            case "math.mod" -> t -> {
                float d = y.eval(t);
                return d == 0 ? 0 : x.eval(t) % d;
            };
            case "math.clamp" -> t -> Math.max(y.eval(t), Math.min(z.eval(t), x.eval(t)));
            case "math.lerp" -> t -> {
                float from = x.eval(t);
                return from + (y.eval(t) - from) * z.eval(t);
            };
            default -> ZERO;
        };
    }
}

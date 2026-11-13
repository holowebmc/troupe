package dev.holoweb.troupe.lua;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code zone} module's areas (boxes and spheres in one dimension) and who is inside each. The game glue feeds
 * player positions a few times a second; a player crossing a border is an enter or a leave for the zone's resource.
 * ponytail: every player against every zone; a grid by chunk when a server has thousands of zones.
 */
public final class Zones {
    @FunctionalInterface
    public interface Sink {
        void event(String resource, boolean enter, long zone);
    }

    private static final class Zone {
        String resource, dim;
        double[] min, max;    // box
        double[] center;      // sphere (min is null)
        double radius;
        final Set<String> inside = new LinkedHashSet<>();

        boolean contains(String dim, double x, double y, double z) {
            if (!this.dim.equals(dim)) return false;
            if (min != null) return x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
            double dx = x - center[0], dy = y - center[1], dz = z - center[2];
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    private final Map<Long, Zone> zones = new LinkedHashMap<>();
    private long nextId;

    /** {@code spec}: {@code { box = { a, b } }} (two opposite corners) or {@code { sphere = center, radius = n }}. */
    public long create(String resource, String dim, Map<String, Object> spec) {
        Zone z = new Zone();
        z.resource = resource;
        z.dim = dim;
        if (spec.get("box") instanceof List<?> corners && corners.size() == 2) {
            double[] a = point(corners.get(0)), b = point(corners.get(1));
            z.min = new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
            z.max = new double[]{Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        } else if (spec.get("sphere") != null) {
            z.center = point(spec.get("sphere"));
            if (!(spec.get("radius") instanceof Number r) || !(r.doubleValue() > 0)) throw new IllegalArgumentException("radius must be > 0");
            z.radius = r.doubleValue();
        } else {
            throw new IllegalArgumentException("a zone is a box (two corners) or a sphere (center and radius)");
        }
        zones.put(++nextId, z);
        return nextId;
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    /** Removing a zone is not a leave for who is inside. */
    public void remove(String resource, long id) {
        Zone z = zones.get(id);
        if (z != null && z.resource.equals(resource)) zones.remove(id);
    }

    public void removeAll(String resource) {
        zones.values().removeIf(z -> z.resource.equals(resource));
    }

    public List<String> inside(String resource, long id) {
        Zone z = zones.get(id);
        return z == null || !z.resource.equals(resource) ? List.of() : List.copyOf(z.inside);
    }

    /** Where {@code player} is now. */
    public void update(String player, String dim, double x, double y, double z, Sink sink) {
        for (Map.Entry<Long, Zone> e : new ArrayList<>(zones.entrySet())) {
            Zone zone = e.getValue();
            boolean in = zone.contains(dim, x, y, z);
            if (in ? zone.inside.add(player) : zone.inside.remove(player)) sink.event(zone.resource, in, e.getKey());
        }
    }

    /** {@code player} left the server: a leave for every zone they were in. */
    public void left(String player, Sink sink) {
        for (Map.Entry<Long, Zone> e : new ArrayList<>(zones.entrySet()))
            if (e.getValue().inside.remove(player)) sink.event(e.getValue().resource, false, e.getKey());
    }

    /** A position from a script: {@code { x, y, z }} by name (what {@code player:pos()} returns) or in order. */
    public static double[] point(Object o) {
        if (o instanceof List<?> l && l.size() >= 3 && l.get(0) instanceof Number x && l.get(1) instanceof Number y && l.get(2) instanceof Number z)
            return new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
        if (o instanceof Map<?, ?> m && m.get("x") instanceof Number x && m.get("y") instanceof Number y && m.get("z") instanceof Number z)
            return new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
        throw new IllegalArgumentException("position expected: { x, y, z }");
    }
}

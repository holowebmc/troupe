package dev.holoweb.troupe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Key mappings scripts turned off with {@code keys.block(name)}: the game's ('key.inventory', 'key.swapOffhand') or
 * another mod's, by the name it registered. {@code KeyBlockMixin} makes a blocked mapping never pressed nor clicked,
 * which covers everything that reads it through {@code isDown} / {@code consumeClick}; a mod reading raw key events
 * still sees the key. Render thread.
 */
public final class KeyBlocks {
    private static final Map<String, Set<String>> byResource = new HashMap<>();
    private static volatile Set<String> blocked = Set.of();

    private KeyBlocks() {}

    public static boolean blocked(String name) {
        Set<String> b = blocked;
        return !b.isEmpty() && b.contains(name);
    }

    static void set(String resource, String name, boolean on) {
        Set<String> mine = byResource.computeIfAbsent(resource, r -> new HashSet<>());
        if (on) mine.add(name);
        else mine.remove(name);
        update();
    }

    /** A resource stopped (null: leaving the server): its blocks go. */
    static void release(String resource) {
        if (resource == null) byResource.clear();
        else byResource.remove(resource);
        update();
    }

    private static void update() {
        Set<String> all = new HashSet<>();
        byResource.values().forEach(all::addAll);
        blocked = Set.copyOf(all);
    }
}

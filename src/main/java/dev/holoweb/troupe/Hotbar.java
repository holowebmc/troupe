package dev.holoweb.troupe;

/** player.skip_empty_slots: where the selection goes when it lands on an empty hotbar slot. No game types: HotbarTest. */
final class Hotbar {
    private Hotbar() {}

    /**
     * The slot to stand on instead of {@code cur} when it's empty: the next filled one, going the way the player moved
     * from {@code last} (the short way round the hotbar); {@code cur} itself when filled; -1 when every slot is empty.
     */
    static int skipEmpty(boolean[] filled, int last, int cur) {
        if (filled[cur]) return cur;
        int n = filled.length, d = Math.floorMod(cur - last, n);
        int step = d <= n / 2 ? 1 : -1;
        for (int i = 1; i < n; i++) {
            int s = Math.floorMod(cur + step * i, n);
            if (filled[s]) return s;
        }
        return -1;
    }
}

package dev.holoweb.troupe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotbarTest {
    // slots 0, 1 and 4 filled, as a gun, a pistol and a bomb
    private static final boolean[] FILLED = { true, true, false, false, true, false, false, false, false };

    @Test
    void skipsEmptySlotsTheWayThePlayerMoved() {
        assertEquals(1, Hotbar.skipEmpty(FILLED, 0, 1), "a filled slot stays");
        assertEquals(4, Hotbar.skipEmpty(FILLED, 1, 2), "scrolling forward from 1 lands on 4");
        assertEquals(1, Hotbar.skipEmpty(FILLED, 4, 3), "scrolling back from 4 lands on 1");
        assertEquals(0, Hotbar.skipEmpty(FILLED, 4, 5), "forward past the last filled one wraps to 0");
        assertEquals(4, Hotbar.skipEmpty(FILLED, 0, 8), "back from 0 wraps to 4");
        assertEquals(-1, Hotbar.skipEmpty(new boolean[9], 0, 3), "nothing filled: nowhere to go");
    }
}

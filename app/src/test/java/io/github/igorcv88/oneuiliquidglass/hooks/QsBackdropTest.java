package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QsBackdropTest {
    /** A VISIBLE compose root over a collapsed panel is not an opening; expansion decides. */
    @Test public void openingFollowsTheExpansionNotTheVisibility() {
        assertFalse(QsBackdrop.open(true, 0));
        assertTrue(QsBackdrop.open(true, 0.02));
        assertTrue(QsBackdrop.open(true, 1));
        assertFalse(QsBackdrop.open(false, 1));
        // Unreadable expansion: visibility stands in for it.
        assertTrue(QsBackdrop.open(true, -1));
        assertFalse(QsBackdrop.open(false, -1));
    }
}

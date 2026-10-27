package io.github.igorcv88.oneuiliquidglass.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FrameAccountTest {
    private static final long MS = 1_000_000L, NONE = Long.MIN_VALUE;

    /** onPreDraw (2 ms) calling event() (0.5 ms): the frame holds 2 ms, not 2.5 ms. */
    @Test public void nestedIntervalIsCountedOnce() {
        FrameAccount a = new FrameAccount();
        a.enter();            // predraw
        a.enter();            // event, inside it
        assertEquals(-1, a.exit(MS / 2, 100));
        assertEquals(1, a.depth());
        assertEquals(-1, a.exit(2 * MS, 100));
        assertEquals(0, a.depth());
        // The next frame's first interval closes frame 100 with 2 ms.
        a.enter();
        assertEquals(2 * MS, a.exit(MS, 200));
    }
    /** drawAfter containing glassDraw, then attach containing event: each pair counted once. */
    @Test public void severalOutermostIntervalsAddUp() {
        FrameAccount a = new FrameAccount();
        a.enter(); a.enter(); a.exit(MS, 100); a.exit(3 * MS, 100);   // drawAfter 3 ms (glassDraw 1)
        a.enter(); a.enter(); a.exit(MS, 100); a.exit(2 * MS, 100);   // attach 2 ms (event 1)
        a.enter();
        assertEquals(5 * MS, a.exit(0, 200));
    }
    /** Work outside any frame's callbacks (input, a posted runnable) does not join the last vsync. */
    @Test public void outsideFrameIsSummedApart() {
        FrameAccount a = new FrameAccount();
        a.enter(); a.exit(MS, 100);
        a.enter(); assertEquals(-1, a.exit(4 * MS, NONE));
        assertEquals(4 * MS, a.takeOutside());
        assertEquals(0, a.takeOutside());
        a.enter();
        assertEquals(MS, a.exit(MS, 200));
    }
    /** A nested interval outside a frame is still part of its caller only. */
    @Test public void nestedOutsideFrameCountedOnce() {
        FrameAccount a = new FrameAccount();
        a.enter(); a.enter();
        a.exit(MS, NONE);
        a.exit(3 * MS, NONE);
        assertEquals(3 * MS, a.takeOutside());
    }
    /** An unbalanced exit (enabled turned on mid-call) never drives the depth negative. */
    @Test public void depthNeverNegative() {
        FrameAccount a = new FrameAccount();
        a.exit(MS, 100);
        assertEquals(0, a.depth());
        a.enter();
        assertEquals(MS, a.exit(MS, 200));
    }
}

package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ToneGateTest {
    @Test public void firstDecisionPassesAtOnce() {
        ToneGate g = new ToneGate(150, 400);
        assertNull(g.tone());
        assertTrue(g.offer(true, 0));
        assertEquals(Boolean.TRUE, g.tone());
        assertFalse(g.pending());
    }
    @Test public void aChangeNeedsAConfirmingLaterSample() {
        ToneGate g = new ToneGate(150, 400);
        g.offer(true, 0);
        assertFalse(g.offer(false, 1000));
        assertTrue(g.pending());
        assertEquals(150, g.confirmIn(1000));
        assertFalse("too soon", g.offer(false, 1100));
        assertTrue(g.offer(false, 1150));
        assertEquals(Boolean.FALSE, g.tone());
        assertFalse(g.pending());
    }
    @Test public void noiseBetweenAgreeingSamplesChangesNothing() {
        ToneGate g = new ToneGate(150, 400);
        g.offer(false, 0);
        for (long t = 1000; t < 5000; t += 200) {
            assertFalse(g.offer(true, t));        // one bright frame
            assertFalse(g.offer(false, t + 100)); // back: candidate dropped
            assertFalse(g.pending());
        }
        assertEquals(Boolean.FALSE, g.tone());
    }
    @Test public void dwellLimitsBackToBackChanges() {
        ToneGate g = new ToneGate(150, 400);
        g.offer(true, 0);
        g.offer(false, 100);
        assertFalse("dwell since first decision", g.offer(false, 300));
        assertTrue(g.offer(false, 400));
        g.offer(true, 450);
        assertFalse(g.offer(true, 700));
        assertEquals(100, g.confirmIn(700));
        assertTrue(g.offer(true, 800));
    }
    @Test public void resetMakesTheNextSampleAFirstDecision() {
        ToneGate g = new ToneGate(150, 400);
        g.offer(true, 0); g.offer(false, 10);
        g.reset();
        assertNull(g.tone()); assertFalse(g.pending());
        assertTrue(g.offer(false, 20));
    }
}

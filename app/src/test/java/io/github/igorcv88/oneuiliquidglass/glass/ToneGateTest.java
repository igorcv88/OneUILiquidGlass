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
    @Test public void strongReadingsApplyAtOnceButNotBackToBack() {
        ToneGate g = new ToneGate(150, 600);
        g.offer(false, 0);
        assertTrue(g.offer(true, 200, true));
        assertEquals(Boolean.TRUE, g.tone());
        assertFalse("within the strong dwell", g.offer(false, 250, true));
        assertTrue(g.offer(false, 300, true));
        assertTrue(ToneGate.strong(.95f, .7f));
        assertFalse(ToneGate.strong(.8f, .7f));
        assertFalse(ToneGate.strong(Float.NaN, .7f));
    }
    @Test public void quickConfirmationSkipsConfirmIntervalButKeepsShortDwell() {
        ToneGate g = new ToneGate(150, 600);
        g.offer(false, 0);
        assertFalse(g.offer(true, 1000, false, true));
        assertEquals("candidate", g.lastReason());
        assertTrue(g.offer(true, 1100, false, true));
        assertEquals("task-confirmed", g.lastReason());
        // A commit 50 ms ago: even the quick path waits for STRONG_DWELL_MS.
        assertFalse(g.offer(false, 1120, false, true));
        assertFalse(g.offer(false, 1150, false, true));
        assertEquals("hold-dwell", g.lastReason());
        assertTrue(g.offer(false, 1200, false, true));
    }
    @Test public void quickConfirmationNeedsALaterSample() {
        ToneGate g = new ToneGate(150, 600);
        g.offer(false, 0);
        g.offer(true, 1000, false, true);
        assertFalse(g.offer(true, 1000, false, true));
        assertEquals(Boolean.FALSE, g.tone());
    }
}

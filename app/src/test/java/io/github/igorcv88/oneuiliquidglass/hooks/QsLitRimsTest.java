package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class QsLitRimsTest {
    /** No box is attenuated before its own optics drew; the first success recomposes it once. */
    @Test public void outlineIsFullUntilThisBoxDrewAndChangesOnce() {
        QsLitRims r = new QsLitRims();
        Object a = new Object(), b = new Object();
        assertFalse(r.lit(a)); assertFalse(r.lit(b)); assertFalse(r.lit(null));
        assertTrue(r.report(a, true, 1));
        assertTrue(r.lit(a));
        assertFalse("another box is not attenuated by a's draw", r.lit(b));
        for (int i = 0; i < 100; i++) assertFalse("repeated draws do not recompose", r.report(a, true, 1));
        assertFalse(r.report(null, true, 1));
    }

    /** A failed draw hands the full outline back and is not retried in the same session. */
    @Test public void failureRestoresFullOutlineWithoutOscillation() {
        QsLitRims r = new QsLitRims();
        Object a = new Object();
        assertFalse("a first failure leaves the full outline as it is", r.report(a, false, 1));
        assertFalse(r.report(a, true, 1));
        assertFalse(r.lit(a));
        assertTrue("next session: one more chance", r.report(a, true, 2));
        assertTrue(r.lit(a));
        assertTrue("lit then failed: recompose to full", r.report(a, false, 2));
        assertFalse(r.lit(a));
        assertFalse(r.report(a, true, 2));
        assertFalse(r.report(a, false, 2));
    }

    @Test public void processFailureClearsEveryBox() {
        QsLitRims r = new QsLitRims();
        Object a = new Object(), b = new Object();
        r.report(a, true, 1); r.report(b, true, 1);
        assertEquals(2, r.litCount());
        r.clear();
        assertFalse(r.lit(a)); assertFalse(r.lit(b));
        assertEquals(0, r.litCount());
    }
}

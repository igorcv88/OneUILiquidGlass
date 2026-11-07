package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class NotificationEdgeLineGainTest {
    @Test public void eachNotificationSurfaceReceivesOnlyItsOwnGain() {
        assertEquals(1.30f, NotificationEdgeLineGain.select(true, true, false, 1.30f, 0.80f, 1.30f), 0.00001f);
        assertEquals(0.80f, NotificationEdgeLineGain.select(true, false, true, 1.30f, 0.80f, 1.30f), 0.00001f);
        assertEquals(1.30f, NotificationEdgeLineGain.select(true, false, false, 1.30f, 0.80f, 1.30f), 0.00001f);
    }

    @Test public void neverChangesOverflowOrOtherLiveSurfaces() {
        assertEquals(1f, NotificationEdgeLineGain.select(false, true, false, 1.30f, 0.80f, 1.30f), 0f);
        assertEquals(1f, NotificationEdgeLineGain.select(false, false, true, 1.30f, 0.80f, 1.30f), 0f);
        assertEquals(1f, NotificationEdgeLineGain.select(false, false, false, 1.30f, 0.80f, 1.30f), 0f);
    }

    @Test public void neutralTuningCanRestoreEveryOriginalLine() {
        for (boolean popup : new boolean[] {false, true})
            for (boolean lock : new boolean[] {false, true})
                assertEquals(1f, NotificationEdgeLineGain.select(true, popup, lock, 1f, 1f, 1f), 0f);
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QsSliderLengthHistoryTest {
    @Test public void sameDrawScopeDoesNotCompareBrightnessAgainstVolume() {
        QsSliderLengthHistory lengths = new QsSliderLengthHistory();
        Object sharedScope = new Object();
        assertNull(lengths.replace(sharedScope, QsSliderOrigin.BRIGHTNESS, 120f));
        assertNull(lengths.replace(sharedScope, QsSliderOrigin.VOLUME, 45f));
        assertEquals(120f, lengths.replace(sharedScope, QsSliderOrigin.BRIGHTNESS, 120.01f), 0f);
        assertEquals(45f, lengths.replace(sharedScope, QsSliderOrigin.VOLUME, 185f), 0f);
        assertEquals(120.01f, lengths.replace(sharedScope, QsSliderOrigin.BRIGHTNESS, 120.02f), 0f);
    }

    @Test public void unknownRoleCannotContaminateKnownBars() {
        QsSliderLengthHistory lengths = new QsSliderLengthHistory();
        Object sharedScope = new Object();
        assertNull(lengths.replace(sharedScope, QsSliderOrigin.UNKNOWN, 70f));
        assertNull(lengths.replace(sharedScope, QsSliderOrigin.BRIGHTNESS, 100f));
        assertEquals(70f, lengths.replace(sharedScope, QsSliderOrigin.UNKNOWN, 90f), 0f);
        assertEquals(100f, lengths.replace(sharedScope, QsSliderOrigin.BRIGHTNESS, 100.1f), 0f);
    }

    @Test public void oneScopeCannotContaminateAnother() {
        QsSliderLengthHistory lengths = new QsSliderLengthHistory();
        Object first = new Object(), second = new Object();
        assertNull(lengths.replace(first, QsSliderOrigin.BRIGHTNESS, 100f));
        assertNull(lengths.replace(second, QsSliderOrigin.BRIGHTNESS, 50f));
        assertEquals(100f, lengths.replace(first, QsSliderOrigin.BRIGHTNESS, 101f), 0f);
        assertEquals(50f, lengths.replace(second, QsSliderOrigin.BRIGHTNESS, 52f), 0f);
    }
}

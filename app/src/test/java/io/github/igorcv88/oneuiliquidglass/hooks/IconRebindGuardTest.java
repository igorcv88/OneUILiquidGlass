package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;
import org.junit.Test;

public class IconRebindGuardTest {
    @Test public void repeatedReplacementsTripAndStayClosed() {
        IconRebindGuard guard = new IconRebindGuard();
        assertFalse(guard.record(1000, 2));
        assertFalse(guard.record(1033, 2));
        assertFalse(guard.record(1066, 2));
        assertFalse(guard.record(1099, 2));
        assertTrue(guard.record(1132, 2));
        assertEquals(10, guard.count());
        assertTrue(guard.record(5000, 0));
        guard.reset();
        assertFalse(guard.tripped());
    }

    @Test public void ordinaryRebindsOutsideWindowDoNotAccumulate() {
        IconRebindGuard guard = new IconRebindGuard();
        for (int i = 0; i < 40; i++) assertFalse(guard.record(1000 + i * 1000L, 2));
        assertEquals(2, guard.count());
    }

    @Test public void aNewNotificationCanReuseRowAfterCircuitTrip() {
        IconRebindGuard guard = new IconRebindGuard();
        assertTrue(guard.bindNotification("0|com.igcv.gradus|998|null|10432"));
        assertTrue(guard.record(1000, 9));
        assertFalse("same notification must remain protected",
                guard.bindNotification("0|com.igcv.gradus|998|null|10432"));
        assertTrue(guard.tripped());
        assertTrue("recycled row may treat its new notification",
                guard.bindNotification("0|com.sec.android.app.clockpackage|0|null|10472"));
        assertFalse(guard.tripped());
        assertFalse(guard.record(2000, 1));
    }
}

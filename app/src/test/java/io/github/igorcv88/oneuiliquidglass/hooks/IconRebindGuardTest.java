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
}

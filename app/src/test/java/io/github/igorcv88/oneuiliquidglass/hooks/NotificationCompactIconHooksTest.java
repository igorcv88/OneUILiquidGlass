package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The overflow dot needs its own slot after the user-configured real icon limit. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NotificationCompactIconHooksTest {
    @Test public void fourthIconReservesDotSpaceWithoutMiscountingTheLimit() {
        assertEquals(270, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 4));
        assertEquals(270, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 3));
    }

    @Test public void nativeOrUnmeasuredStatusBarDoesNotForceAnyWidth() {
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(0, 54, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 0, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 0));
    }
}

package io.github.igorcv88.oneuiliquidglass.config;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationIconPolicyTest {
    @Test public void modeClampsToConservativeDefault() {
        assertEquals(NotificationIconPolicy.SYSTEM, NotificationIconPolicy.mode(-1));
        assertEquals(NotificationIconPolicy.SYSTEM, NotificationIconPolicy.mode(17));
        assertEquals(NotificationIconPolicy.ORIGINAL_ONLY, NotificationIconPolicy.mode(2));
    }

    @Test public void defaultPreservesUserInstalledIconPacks() {
        assertFalse(NotificationIconPolicy.eligible(1, false, false));
        assertFalse(NotificationIconPolicy.eligible(1, false, true));
        assertTrue(NotificationIconPolicy.eligible(1, true, false));
        assertFalse(NotificationIconPolicy.eligible(0, true, true));
        assertTrue(NotificationIconPolicy.systemService("com.android.shell", false));
        assertFalse(NotificationIconPolicy.systemService("com.example.iconpack", false));
    }

    @Test public void originalOnlyRefusesAmbiguousIcons() {
        assertFalse(NotificationIconPolicy.eligible(2, false, false));
        assertTrue(NotificationIconPolicy.eligible(2, false, true));
        assertTrue(NotificationIconPolicy.eligible(3, false, false));
    }

    @Test public void onlyCompletelyUnresolvedIconsAreMissing() {
        assertFalse(NotificationIconPolicy.missing(false, false, false, false, false, false));
        assertTrue(NotificationIconPolicy.missing(true, false, false, false, false, false));
        assertFalse(NotificationIconPolicy.missing(true, false, true, false, false, false));
        assertFalse(NotificationIconPolicy.missing(true, false, false, false, true, false));
        assertFalse(NotificationIconPolicy.missing(true, false, false, false, false, true));
    }
}

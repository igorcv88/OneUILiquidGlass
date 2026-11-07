package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationIconOwnerTest {
    @Test public void onlyPrimaryBackgroundOwnsIcons() {
        Object normal = new Object(), shadow = new Object(), dimmed = new Object();
        assertTrue(NotificationIconOwner.isNormal(normal, normal));
        assertFalse(NotificationIconOwner.isNormal(shadow, normal));
        assertFalse(NotificationIconOwner.isNormal(dimmed, normal));
    }

    @Test public void missingBackgroundNeverClaimsIcons() {
        assertFalse(NotificationIconOwner.isNormal(null, null));
        assertFalse(NotificationIconOwner.isNormal(new Object(), null));
    }
}

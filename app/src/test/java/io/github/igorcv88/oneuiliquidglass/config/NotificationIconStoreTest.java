package io.github.igorcv88.oneuiliquidglass.config;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationIconStoreTest {
    @Test public void exportedPathAcceptsOnlyPackageIdentifiers() {
        assertTrue(NotificationIconStore.validPackage("android"));
        assertTrue(NotificationIconStore.validPackage("com.android.shell"));
        assertTrue(NotificationIconStore.validPackage("com.samsung.android.app.motion"));
        assertFalse(NotificationIconStore.validPackage("../files/private"));
        assertFalse(NotificationIconStore.validPackage("com.app/../../secret"));
        assertFalse(NotificationIconStore.validPackage(""));
        assertFalse(NotificationIconStore.validPackage(null));
        assertFalse(NotificationIconStore.validPackage("a.b?token=secret"));
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.Looper;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class QsCapsuleOpticsTest {
    @After public void reset() throws Exception {
        Field f = QsCapsuleOptics.class.getDeclaredField("broken"); f.setAccessible(true); f.set(null, false);
    }
    /** A failure stops the optics once and hands the full rim back on the main thread. */
    @Test public void failureDisablesTheOpticsAndRestoresTheRimOnce() {
        QsCapsuleOptics optics = new QsCapsuleOptics();
        int[] restored = {0};
        optics.onFailure(() -> restored[0]++);
        assertTrue(QsCapsuleOptics.available());
        optics.fail("geometry", new IllegalStateException("test"));
        assertFalse(QsCapsuleOptics.available());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, restored[0]);
        optics.fail("shader", new IllegalStateException("again"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, restored[0]);
    }
}

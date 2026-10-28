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
    /** A late shader overlay must leave the capsule centre untouched, including on small tiles. */
    @Test public void fallbackBevelMaskNeverCoversTheCentre() {
        assertEquals(20f, QsCapsuleOptics.overlayBand(660f, 290f, 1f, 20f), 0.001f);
        assertEquals(19.2f, QsCapsuleOptics.overlayBand(80f, 80f, 3f, 20f), 0.001f);
        assertTrue(QsCapsuleOptics.overlayBand(100f, 60f, 2f, 20f) < 60f / 4f);
        assertEquals(0f, QsCapsuleOptics.overlayBand(Float.NaN, 100f, 1f, 20f), 0f);
        assertEquals(0f, QsCapsuleOptics.overlayBand(100f, 100f, 0f, 20f), 0f);
    }

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

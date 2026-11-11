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
import io.github.igorcv88.oneuiliquidglass.glass.LiquidGlassShader;

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
    @Test public void qsRemovesOpposedCornerArcWithoutChangingShadeShader(){
        assertTrue(LiquidGlassShader.SOURCE.contains("* innerGain"));
        assertFalse(QuickSettingsEdgeLight.usesCompositor("media"));
        assertFalse(QuickSettingsEdgeLight.usesCompositor("media-empty"));
        assertTrue(QuickSettingsEdgeLight.usesCompositor("slider"));
        assertTrue(QuickSettingsEdgeLight.usesCompositor("tile"));
    }

    /** The QS-only angular wrap carries the fixed top light into the curved rim. */
    @Test public void directionalCornerWrapPreservesDirectionAndDynamicLightSeparation() {
        assertTrue(LiquidGlassShader.SOURCE.contains("uniform float qsDirectionalWrap;"));
        assertTrue(LiquidGlassShader.SOURCE.contains("smoothstep(-0.65, 1.0, facing)"));
        assertTrue(LiquidGlassShader.SOURCE.contains("qsDirectionalWrap > 0.5 ? 0.97 : 1.0"));
        // With the current top-left direction, top-right curvature must not lose
        // all of its static light, but still receive less than the straight top.
        float x = 0.45f, y = 0.89f;
        float length = (float) Math.hypot(x, y);
        float top = y / length;
        float topRight = (y - x) / (length * (float) Math.sqrt(2.0));
        float rightSide = -x / length;
        float bottom = -y / length;
        assertTrue(wrapped(topRight) > original(topRight));
        assertTrue(wrapped(topRight) < wrapped(top));
        assertTrue(wrapped(rightSide) < wrapped(topRight));
        assertEquals(0f, wrapped(bottom), 0.0001f);
    }

    private static float original(float facing) { return smooth(-0.35f, 1f, facing); }
    private static float wrapped(float facing) {
        return original(facing) * 0.2f + smooth(-0.65f, 1f, facing) * 0.8f;
    }
    private static float smooth(float lo, float hi, float value) {
        float t = Math.max(0f, Math.min(1f, (value - lo) / (hi - lo)));
        return t * t * (3f - 2f * t);
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

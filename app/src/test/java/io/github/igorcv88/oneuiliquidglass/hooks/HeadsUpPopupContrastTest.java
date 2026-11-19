package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.glass.Material;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGate;
import io.github.igorcv88.oneuiliquidglass.glass.VeilRamp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "night")
public class HeadsUpPopupContrastTest {
    @Test public void liveDrawableMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(true, false, true, false, false));
        contrastChangesWithBackdrop();
    }

    @Test public void samsungLensMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(false, true, false, true, false));
        contrastChangesWithBackdrop();
    }

    @Test public void sfLensOnCardMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(false, true, true, false, true));
        contrastChangesWithBackdrop();
    }

    @Test public void unrelatedDrawablesAndMissingBridgesDoNotEnterPopupTone() {
        assertFalse(HeadsUpHooks.popupContrastRoute(false, true, true, false, false));
        assertFalse(HeadsUpHooks.popupContrastRoute(false, true, false, false, false));
        assertFalse(HeadsUpHooks.popupContrastRoute(false, false, true, false, true));
        assertFalse(HeadsUpHooks.popupContrastRoute(true, false, false, true, false));
    }

    private static void contrastChangesWithBackdrop() {
        assertTrue(ShadeTone.lightText());
        ToneGate gate = new ToneGate(100, 220);
        Material dark = ShadeTone.materialFor(0.10f, null, false);
        assertFalse(dark.light);
        assertFalse(dark.swap);
        assertTrue(gate.offer(dark.light, 0));

        float lightReading = 0.67f;
        boolean lightSide = ShadeTone.lightBackdrop(lightReading, dark.light);
        assertTrue(lightSide);
        assertFalse(gate.offer(lightSide, 220));
        assertFalse(gate.offer(lightSide, 319));
        assertTrue(gate.offer(lightSide, 320));

        Material light = ShadeTone.materialFor(lightReading, dark.light, false);
        assertTrue(light.light);
        assertTrue(light.swap);
        assertNotEquals(0, light.veil >>> 24);
        int nativeTint = 0x20102030;
        assertArrayEquals(new int[] {nativeTint, light.veil}, VeilRamp.ends(dark.veil, light.veil, nativeTint));

        float darkReading = 0.42f;
        boolean darkSide = ShadeTone.lightBackdrop(darkReading, light.light);
        assertFalse(darkSide);
        assertFalse(gate.offer(darkSide, 540));
        assertTrue(gate.offer(darkSide, 640));
        Material restored = ShadeTone.materialFor(darkReading, light.light, false);
        assertFalse(restored.light);
        assertFalse(restored.swap);
        assertArrayEquals(new int[] {light.veil, nativeTint}, VeilRamp.ends(light.veil, restored.veil, nativeTint));
    }
}

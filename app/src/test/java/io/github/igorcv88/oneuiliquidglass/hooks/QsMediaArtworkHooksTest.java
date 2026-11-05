package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import org.junit.Test;

public class QsMediaArtworkHooksTest {
    @Test public void onlyPageArtworkIsEligible() {
        assertTrue(QsMediaArtworkHooks.matches(QsMediaArtworkHooks.PAGE,
                QsMediaArtworkHooks.BITMAP_PAINTER));
        assertFalse(QsMediaArtworkHooks.matches("media.components.OneUI90MediaPlayerInfoKt",
                QsMediaArtworkHooks.BITMAP_PAINTER));
        assertFalse(QsMediaArtworkHooks.matches(QsMediaArtworkHooks.PAGE,
                "androidx.compose.ui.graphics.vector.VectorPainter"));
        assertFalse(QsMediaArtworkHooks.matches("tiles.IconTextTileKt",
                QsMediaArtworkHooks.BITMAP_PAINTER));
    }

    @Test public void canonicalConstructorOnlyAvoidsDoubleScalingFromSyntheticOverloads() {
        assertTrue(QsMediaArtworkHooks.alphaSlot(new Object[]{new Object(), true,
                new Object(), new Object(), 1f, null}));
        assertFalse(QsMediaArtworkHooks.alphaSlot(new Object[]{new Object(), true,
                new Object(), new Object(), 1f, null, 0, null}));
        assertFalse(QsMediaArtworkHooks.alphaSlot(new Object[]{new Object(), true,
                new Object(), new Object(), "1", null}));
        assertFalse(QsMediaArtworkHooks.alphaSlot(null));
    }

    @Test public void nativeAlphaIsMultipliedWithoutTouchingOtherControls() {
        assertEquals(0.45f, QsMediaArtworkHooks.opacity(1f, 0.45f, true), 0.0001f);
        assertEquals(0.27f, QsMediaArtworkHooks.opacity(0.6f, 0.45f, true), 0.0001f);
        assertEquals(0.05f, QsMediaArtworkHooks.opacity(1f, 0.01f, true), 0.0001f);
        assertEquals(1f, QsMediaArtworkHooks.opacity(1f, 2f, true), 0.0001f);
    }

    @Test public void noGlassOrMasterDisabledPreservesNative() {
        assertEquals(0.8f, QsMediaArtworkHooks.opacity(0.8f, Tuning.MEDIA_ART_NATIVE, true), 0f);
        assertEquals(0.8f, QsMediaArtworkHooks.opacity(0.8f, 0.45f, false), 0f);
        assertEquals(0.8f, QsMediaArtworkHooks.opacity(0.8f, Float.NaN, true), 0f);
    }
}

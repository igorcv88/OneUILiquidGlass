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

    @Test public void probeReportsOnlyMediaSources() {
        assertTrue(QsMediaArtworkHooks.mediaSource(
                "com.android.systemui.samsung.quicksetting.ui.media.components.OneUI90MediaPlayerPageKt$Art$1", null));
        assertTrue(QsMediaArtworkHooks.mediaSource("androidx.compose.Foo$1", "media.components.OneUI90MediaPlayerInfoKt"));
        assertFalse(QsMediaArtworkHooks.mediaSource(
                "com.android.systemui.samsung.quicksetting.ui.tiles.QuickTileKt$1", "tiles.QuickTileKt"));
        assertFalse(QsMediaArtworkHooks.mediaSource("androidx.compose.Foo$1", null));
    }
    @Test public void qsKeepsLegacyDefaultAndPropertyValues() {
        assertEquals(.25f, io.github.igorcv88.oneuiliquidglass.glass.Tuning.qsMediaArtOpacity(null), 0f);
        assertEquals(.25f, io.github.igorcv88.oneuiliquidglass.glass.Tuning.qsMediaArtOpacity("bad"), 0f);
        assertEquals(.65f, io.github.igorcv88.oneuiliquidglass.glass.Tuning.qsMediaArtOpacity("0.65"), 0f);
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.Tuning.MEDIA_ART_NATIVE,
                io.github.igorcv88.oneuiliquidglass.glass.Tuning.qsMediaArtOpacity("off"), 0f);
        assertEquals(.50f, io.github.igorcv88.oneuiliquidglass.glass.Tuning.mediaArtOpacity(null), 0f);
    }


    @Test public void theLoadedArtworkPainterIsEligible() {
        assertTrue(QsMediaArtworkHooks.matches(QsMediaArtworkHooks.PAGE, QsMediaArtworkHooks.DRAWABLE_PAINTER));
        assertFalse("placeholder while loading", QsMediaArtworkHooks.matches(QsMediaArtworkHooks.PAGE,
                "com.android.compose.ui.graphics.painter.EmptyPainter"));
        assertFalse("header icons keep their painter", QsMediaArtworkHooks.matches(
                "media.components.OneUI90MediaPlayerHeaderKt", QsMediaArtworkHooks.DRAWABLE_PAINTER));
    }

    @Test public void onlyTheArtworkBlockIsReplacedAndOnlyWhileTranslucent() {
        assertTrue(QsMediaArtworkHooks.artworkBlock(QsMediaArtworkHooks.ARTWORK_BLOCK + "$$ExternalSyntheticLambda4"));
        assertFalse(QsMediaArtworkHooks.artworkBlock(
                "com.android.systemui.samsung.quicksetting.ui.media.components.OneUI90MediaPlayerPageKt$1"));
        assertFalse(QsMediaArtworkHooks.artworkBlock(null));
        assertTrue(QsMediaArtworkHooks.dropOverlay(true, 0.45f, null));
        assertFalse("module off", QsMediaArtworkHooks.dropOverlay(false, 0.45f, null));
        assertFalse("native art", QsMediaArtworkHooks.dropOverlay(true, Tuning.MEDIA_ART_NATIVE, null));
        assertFalse("opaque art keeps Samsung's block", QsMediaArtworkHooks.dropOverlay(true, 1f, null));
        assertFalse("opt-out", QsMediaArtworkHooks.dropOverlay(true, 0.45f, "native"));
    }
}

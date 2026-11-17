package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Laudo 17/11 section 5: the live notification card's outer line over a saturated body. The body's
 * colour under the line is mixed toward its own grey with SRC_ATOP (alpha and luma kept), only for
 * CARD, only under the line, and nothing changes for the other hosts.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveRimNeutralTest {
    private static final class Recorded { int color, alpha; float width; BlendMode mode; }
    /** Records the SRC_ATOP path strokes; the rest draws as usual. */
    private static final class Recording extends TestCanvas {
        final List<Recorded> atop = new ArrayList<>();
        Recording() { super(400, 120); }
        @Override public void drawPath(Path p, Paint paint) {
            if (paint.getBlendMode() == BlendMode.SRC_ATOP) {
                Recorded r = new Recorded();
                r.color = paint.getColor() | 0xff000000; r.alpha = paint.getAlpha(); r.width = paint.getStrokeWidth(); r.mode = paint.getBlendMode();
                atop.add(r);
            }
            super.drawPath(p, paint);
        }
    }
    private static double luma(int c) { return 0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255); }
    private static int chroma(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    @Test public void greyKeepsLumaAndMixKeepsAlphaAndLuma() {
        for (int c : new int[] {0xffd32f2f, 0xfff57c00, 0xff388e3c, 0xff1976d2, 0x6bffffff, 0x6b000000}) {
            int grey = LiveGlassDrawable.neutralGrey(c);
            assertEquals(0, chroma(grey));
            assertEquals(luma(c), luma(grey), 0.6);
            for (float k : new float[] {0f, 0.4f, 0.8f, 1f}) {
                int m = LiveGlassDrawable.neutralised(c, k);
                assertEquals("alpha kept", c >>> 24, m >>> 24);
                assertEquals("luma kept", luma(c), luma(m), 1.0);
                assertEquals("chroma scaled by 1 - k", chroma(c) * (1 - k), chroma(m), 1.5);
            }
        }
    }

    @Test public void onlyTheLiveCardNeutralisesItsLine() {
        assertEquals(0.8f, LiveSurface.CARD.spec.rimNeutral, 0f);
        assertEquals(0f, LiveSurface.CHIP.spec.rimNeutral, 0f);
        assertEquals(0f, LiveSurface.NOWBAR.spec.rimNeutral, 0f);
        assertEquals(0f, LiveSurface.TOAST.spec.rimNeutral, 0f);
    }

    private static Recording draw(LiveSurface s, int color, boolean regionless) throws Exception {
        ColorDrawable bg = new ColorDrawable(color);
        LiveGlassDrawable d = new LiveGlassDrawable(bg, NativeShape.uniform(24f), color, s.spec, 3f);
        d.setNative(bg);
        d.setBounds(0, 0, 400, 120);
        d.setBlur(BackgroundBlurBridge.of(new FakeBlurDrawable()));
        if (regionless) { d.setRegionless(true); d.setNativeRegionActive(() -> true); }
        Recording c = new Recording();
        d.draw(c);
        return c;
    }

    @Test public void cardStrokesTheLineWithItsOwnGreyAtopTheRegion() throws Exception {
        int red = 0xffd32f2f;
        Recording c = draw(LiveSurface.CARD, red, false);
        assertEquals(2, c.atop.size());
        int grey = LiveGlassDrawable.neutralGrey(red);
        Recorded soft = c.atop.get(0), core = c.atop.get(1);
        assertEquals(grey, soft.color); assertEquals(grey, core.color);
        assertEquals(Math.round(255 * 0.8f), core.alpha);
        assertEquals(Math.round(255 * 0.4f), soft.alpha);
        assertTrue("the soft band reaches deeper than the core", soft.width > core.width);
        // hairline 0.95 dp at density 3 = 2.85 px; sigma = 0.75 x 2.85; core 2 sigma, soft 4 sigma.
        assertEquals(2f * 0.75f * 2.85f, core.width, 0.01f);
    }

    @Test public void regionlessCardNeutralisesItsTranslucentNativeToo() throws Exception {
        int green = 0xff388e3c;
        Recording c = draw(LiveSurface.CARD, green, true);
        assertEquals(2, c.atop.size());
        assertEquals(LiveGlassDrawable.neutralGrey(green), c.atop.get(1).color);
    }

    @Test public void otherHostsDrawNothingExtra() throws Exception {
        for (LiveSurface s : new LiveSurface[] {LiveSurface.CHIP, LiveSurface.NOWBAR, LiveSurface.TOAST})
            assertEquals(s.name(), 0, draw(s, 0xffd32f2f, false).atop.size());
    }

    @Test public void transparentRegionDrawsNothingExtra() throws Exception {
        assertEquals(0, draw(LiveSurface.CARD, 0x00d32f2f, false).atop.size());
    }
}

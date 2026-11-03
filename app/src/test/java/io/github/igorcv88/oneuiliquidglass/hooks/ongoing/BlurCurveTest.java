package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.BlurCurve;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The neutral curve reaches the firmware's real setter, and is never claimed without one. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class BlurCurveTest {
    static final float[] NEUTRAL = {0, 0, 0, 255, 0, 255};

    /** The common blur setters every variant has. */
    public static class Base extends Drawable {
        public void setBlurRadius(int r) { }
        public void setColor(int c) { }
        public void setCornerRadius(float a, float b, float c, float d) { }
        @Override public void draw(Canvas c) { }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    /** Older / AOSP-like: six floats. */
    public static class Six extends Base {
        float[] got;
        public void setColorCurve(float s, float c, float x0, float x1, float y0, float y1) { got = new float[]{s, c, x0, x1, y0, y1}; }
    }
    /** A curve object with a six-float constructor. */
    public static class Curve {
        final float[] v;
        public Curve(float s, float c, float x0, float x1, float y0, float y1) { v = new float[]{s, c, x0, x1, y0, y1}; }
    }
    public static class ObjectCurve extends Base {
        Curve got;
        public void setBlurColorCurve(Curve c) { got = c; }
    }
    /** A one-argument setter of a type this module cannot build: unsupported. */
    public static class Opaque extends Base {
        boolean called;
        public void setBlurColorCurve(String unknown) { called = true; }
    }
    /** No curve setter at all. */
    public static class Plain extends Base { }

    private static boolean drawWith(Drawable d) throws Exception {
        BackgroundBlurBridge b = BackgroundBlurBridge.of(d);
        b.setNeutralCurve(true);
        b.update(40, 0x40ffffff, new float[8]);
        return b.curveApplied();
    }

    @Test public void dzifSingleArgumentFloatArraySetter() throws Exception {
        FakeBlurDrawable d = new FakeBlurDrawable();
        assertTrue(drawWith(d));
        assertArrayEquals(NEUTRAL, d.curve, 0f);
        assertEquals("setBlurColorCurve(float[])", BlurCurve.forClass(FakeBlurDrawable.class).describe());
    }

    @Test public void sixFloatSetter() throws Exception {
        Six d = new Six();
        assertTrue(drawWith(d));
        assertArrayEquals(NEUTRAL, d.got, 0f);
    }

    @Test public void curveObjectWithSixFloatConstructor() throws Exception {
        ObjectCurve d = new ObjectCurve();
        assertTrue(drawWith(d));
        assertArrayEquals(NEUTRAL, d.got.v, 0f);
    }

    @Test public void unsupportedOrMissingApiIsNeverClaimed() throws Exception {
        Opaque o = new Opaque();
        assertFalse(drawWith(o));
        assertFalse("nothing sent through a setter of unknown type", o.called);
        assertFalse(drawWith(new Plain()));
        assertEquals("none", BlurCurve.forClass(Plain.class).describe());
    }

    @Test public void requestedButNotSentUntilTheRegionIsUpdated() throws Exception {
        BackgroundBlurBridge b = BackgroundBlurBridge.of(new FakeBlurDrawable());
        b.setNeutralCurve(true);
        assertTrue(b.neutralCurve());
        assertFalse(b.curveApplied());
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NativeRegionLeaseTest {
    /** Models BackgroundBlurDrawable's alpha independently of ColorDrawable colour modulation. */
    public static final class NativeBlurDrawable extends android.graphics.drawable.Drawable {
        private int alpha = 255;
        @Override public void setAlpha(int alpha) { this.alpha = alpha; }
        @Override public int getAlpha() { return alpha; }
        @Override public void draw(android.graphics.Canvas canvas) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private View layer() { return new View(Robolectric.buildActivity(Activity.class).setup().get()); }

    @Test public void sharedRegionIsRestoredOnlyAfterTheLastOwnerLeaves() {
        View layer = layer();
        NativeBlurDrawable d = new NativeBlurDrawable();
        d.setAlpha(173);
        layer.setBackground(d);
        NativeRegionLease first = new NativeRegionLease(), second = new NativeRegionLease();
        first.sync(layer, true);
        second.sync(layer, true);
        assertEquals(0, d.getAlpha());
        first.restore();
        assertEquals(0, d.getAlpha());
        second.restore();
        assertEquals(173, d.getAlpha());
        second.restore();
        assertEquals(173, d.getAlpha());
    }

    @Test public void replacementRestoresOldRegionAndTracksLatestNativeAlpha() {
        View layer = layer();
        NativeBlurDrawable old = new NativeBlurDrawable();
        old.setAlpha(120);
        layer.setBackground(old);
        NativeRegionLease lease = new NativeRegionLease();
        lease.sync(layer, true);
        NativeBlurDrawable next = new NativeBlurDrawable();
        next.setAlpha(180);
        layer.setBackground(next);
        lease.sync(layer, true);
        assertEquals(120, old.getAlpha());
        assertEquals(0, next.getAlpha());
        next.setAlpha(90);
        lease.sync(layer, true);
        lease.restore();
        assertEquals(90, next.getAlpha());
    }

    @Test public void initiallyTransparentNativeRegionRemainsTransparent() {
        View layer = layer();
        NativeBlurDrawable d = new NativeBlurDrawable();
        d.setAlpha(0);
        layer.setBackground(d);
        NativeRegionLease lease = new NativeRegionLease();
        lease.sync(layer, true);
        lease.restore();
        assertEquals(0, d.getAlpha());
    }

    @Test public void ordinaryBackgroundIsNeverChanged() {
        View layer = layer();
        ColorDrawable d = new ColorDrawable(0xff123456);
        layer.setBackground(d);
        NativeRegionLease lease = new NativeRegionLease();
        lease.sync(layer, true);
        lease.restore();
        assertSame(d, layer.getBackground());
        assertEquals(0xff123456, d.getColor());
    }
}

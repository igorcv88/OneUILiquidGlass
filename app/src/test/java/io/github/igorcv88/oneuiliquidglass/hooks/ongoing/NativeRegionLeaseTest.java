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
    private View layer() { return new View(Robolectric.buildActivity(Activity.class).setup().get()); }

    @Test public void sharedRegionIsRestoredOnlyAfterTheLastOwnerLeaves() {
        View layer = layer();
        Env.SamsungBlurDrawable d = new Env.SamsungBlurDrawable();
        d.setColor(0xff123456);
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
        Env.SamsungBlurDrawable old = new Env.SamsungBlurDrawable();
        old.setColor(0xff123456);
        d.setAlpha(120);
        layer.setBackground(old);
        NativeRegionLease lease = new NativeRegionLease();
        lease.sync(layer, true);
        Env.SamsungBlurDrawable next = new Env.SamsungBlurDrawable();
        next.setColor(0xff123456);
        next.setAlpha(180);
        layer.setBackground(next);
        lease.sync(layer, true);
        assertEquals(120, old.getAlpha());
        assertEquals(0, next.getAlpha());
        next.setColor(0xff123456);
        next.setAlpha(90);
        lease.sync(layer, true);
        lease.restore();
        assertEquals(90, next.getAlpha());
    }

    @Test public void initiallyTransparentNativeRegionRemainsTransparent() {
        View layer = layer();
        Env.SamsungBlurDrawable d = new Env.SamsungBlurDrawable();
        d.setColor(0xff123456);
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

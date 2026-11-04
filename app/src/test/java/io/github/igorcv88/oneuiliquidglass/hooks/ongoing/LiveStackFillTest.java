package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveStackFillTest {
    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(48f);
        return d;
    }

    @Test public void bothStackFillsAreSuppressedWhileMaskAndRippleRemainNative() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x44ffffff), shape(0xffffffff), shape(0xffffffff));
        ripple.addLayer(shape(0xfa3a3a3d));
        frame.setBackground(ripple);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        Drawable white = ((DrawableWrapper) ripple.getDrawable(0)).getDrawable();
        Drawable mask = ripple.findDrawableByLayerId(android.R.id.mask);
        Drawable grey = ((DrawableWrapper) ripple.getDrawable(2)).getDrawable();
        assertFalse(mask instanceof DrawableWrapper);
        assertSame(ripple, frame.getBackground());
        ripple.setBounds(0, 0, 1340, 438);
        Outline outline = new Outline();
        ripple.getDrawable(0).getOutline(outline);
        assertFalse(outline.isEmpty());
        assertEquals(0f, outline.getAlpha(), 0f);
        Drawable hidden = ripple.getDrawable(2);
        lease.sync(frame);
        assertSame(hidden, ripple.getDrawable(2));
        lease.restore();
        assertSame(white, ripple.getDrawable(0));
        assertSame(grey, ripple.getDrawable(2));
        assertSame(mask, ripple.findDrawableByLayerId(android.R.id.mask));
    }

    @Test public void backgroundReplacementRestoresDetachedStackBeforeTakingNewFill() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        RippleDrawable first = new RippleDrawable(ColorStateList.valueOf(0), shape(0xfa3a3a3d), null);
        frame.setBackground(first);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        Drawable original = ((DrawableWrapper) first.getDrawable(0)).getDrawable();
        frame.setBackground(shape(0xff123456));
        lease.sync(frame);
        assertSame(original, first.getDrawable(0));
        assertTrue(frame.getBackground() instanceof GradientDrawable);
    }
}

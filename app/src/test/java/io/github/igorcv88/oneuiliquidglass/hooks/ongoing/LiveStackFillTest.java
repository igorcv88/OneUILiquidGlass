package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
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

    @Test public void mutedStackPreservesNativeRippleLayersOutlineAndChildren() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        frame.setClipToOutline(true);
        TextView content = new TextView(activity);
        content.setText("Card content");
        frame.addView(content);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                shape(0xffffffff), shape(0xffffffff));
        ripple.addLayer(shape(0xfa3a3a3d));
        frame.setBackground(ripple);
        frame.layout(0, 0, 600, 120);
        ripple.setBounds(0, 0, 600, 120);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        Drawable white = ripple.getDrawable(0);
        Drawable mask = ripple.findDrawableByLayerId(android.R.id.mask);
        Drawable grey = ripple.getDrawable(2);
        assertEquals(0, white.getAlpha());
        assertEquals(0, grey.getAlpha());
        assertEquals(255, mask.getAlpha());
        assertSame(ripple, frame.getBackground());
        assertSame("the clipped child subtree must remain attached", content, frame.getChildAt(0));
        assertEquals(View.VISIBLE, content.getVisibility());
        assertEquals(1f, content.getAlpha(), 0f);
        assertTrue(frame.getClipToOutline());
        Outline outline = new Outline();
        white.getOutline(outline);
        assertFalse("transparent paint must keep the native nonempty clip geometry", outline.isEmpty());

        // Simulate two swipes and an alpha writer: the native layers are never replaced.
        for (int i = 0; i < 3; i++) {
            white.setAlpha(190 + i);
            grey.setAlpha(220 + i);
            frame.getViewTreeObserver().dispatchOnPreDraw();
            assertSame(white, ripple.getDrawable(0));
            assertSame(grey, ripple.getDrawable(2));
            assertSame(mask, ripple.findDrawableByLayerId(android.R.id.mask));
            assertEquals(0, white.getAlpha());
            assertEquals(0, grey.getAlpha());
            assertSame(content, frame.getChildAt(0));
        }
        lease.restore();
        assertSame(white, ripple.getDrawable(0));
        assertSame(grey, ripple.getDrawable(2));
        assertEquals(192, white.getAlpha());
        assertEquals(222, grey.getAlpha());
        assertSame(mask, ripple.findDrawableByLayerId(android.R.id.mask));
    }

    @Test public void backgroundReplacementRestoresOldAlphaWithoutChangingItsLayers() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        RippleDrawable first = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xfa3a3a3d), null);
        frame.setBackground(first);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        Drawable original = first.getDrawable(0);
        assertEquals(0, original.getAlpha());
        frame.setBackground(shape(0xff123456));
        lease.sync(frame);
        assertSame(original, first.getDrawable(0));
        assertEquals(255, original.getAlpha());
        assertTrue(frame.getBackground() instanceof GradientDrawable);
    }

    @Test public void twoCardsCanBeReleasedIndependently() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout first = new FrameLayout(activity);
        FrameLayout second = new FrameLayout(activity);
        RippleDrawable a = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xffffffff), null);
        RippleDrawable b = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xfa3a3a3d), null);
        first.setBackground(a);
        second.setBackground(b);
        LiveStackFill leaseA = new LiveStackFill();
        LiveStackFill leaseB = new LiveStackFill();
        leaseA.sync(first);
        leaseB.sync(second);
        assertEquals(0, a.getDrawable(0).getAlpha());
        assertEquals(0, b.getDrawable(0).getAlpha());
        leaseA.restore();
        assertEquals(255, a.getDrawable(0).getAlpha());
        assertEquals("other card stays transparent throughout transition", 0, b.getDrawable(0).getAlpha());
        leaseB.restore();
        assertEquals(255, b.getDrawable(0).getAlpha());
    }

    @Test public void unknownColorsAreNotMuted() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(activity);
        RippleDrawable nativeBackground = new RippleDrawable(ColorStateList.valueOf(0x55ffffff),
                shape(0xff1144aa), null);
        frame.setBackground(nativeBackground);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        assertEquals(255, nativeBackground.getDrawable(0).getAlpha());
        lease.restore();
    }
}

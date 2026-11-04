package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
    private static GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(48f);
        return d;
    }

    @Test public void multipleSwipesKeepTheNativeClipAndCardContent() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(a);
        frame.setClipToOutline(true);
        TextView child = new TextView(a);
        child.setText("Active live notification");
        frame.addView(child);
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
        assertNotNull("white decoration muted", white.getColorFilter());
        assertNotNull("grey decoration muted", grey.getColorFilter());
        assertNull("ripple mask not modified", mask.getColorFilter());
        assertSame(ripple, frame.getBackground());
        assertEquals(255, white.getAlpha());
        assertEquals(255, grey.getAlpha());
        Outline before = new Outline();
        ripple.getOutline(before);
        assertFalse(before.isEmpty());
        float originalOutlineAlpha = before.getAlpha();
        assertTrue("native outline must stay opaque for clipping", originalOutlineAlpha > 0f);

        for (int swipe = 0; swipe < 4; swipe++) {
            // Samsung can replace a filter on rebind without changing the Drawable identity.
            ColorFilter samsung = new PorterDuffColorFilter(0xffddccbb,
                    PorterDuff.Mode.SRC_IN);
            white.setColorFilter(samsung);
            grey.setColorFilter(samsung);
            frame.getViewTreeObserver().dispatchOnPreDraw();
            // Also exercise the direct stackUpdated/regionTint path.
            lease.sync(frame);
            assertSame(white, ripple.getDrawable(0));
            assertSame(grey, ripple.getDrawable(2));
            assertSame(mask, ripple.findDrawableByLayerId(android.R.id.mask));
            assertNotSame(samsung, white.getColorFilter());
            assertNotSame(samsung, grey.getColorFilter());
            assertSame(child, frame.getChildAt(0));
            assertEquals(View.VISIBLE, child.getVisibility());
            assertEquals(1f, child.getAlpha(), 0f);
            assertTrue(frame.getClipToOutline());
            Outline after = new Outline();
            ripple.getOutline(after);
            assertFalse(after.isEmpty());
            assertEquals(originalOutlineAlpha, after.getAlpha(), 0f);
            assertEquals(255, white.getAlpha());
        }
        lease.restore();
        assertSame(white, ripple.getDrawable(0));
        assertSame(grey, ripple.getDrawable(2));
        assertSame(mask, ripple.findDrawableByLayerId(android.R.id.mask));
        assertTrue("restore latest Samsung filter", white.getColorFilter()
                instanceof PorterDuffColorFilter);
        assertEquals(255, white.getAlpha());
    }

    @Test public void replacingBackgroundRestoresOldFilterAndDoesNotReparentChildren() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(a);
        RippleDrawable first = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xfa3a3a3d), null);
        frame.setBackground(first);
        // Capture the content after mutate(), since RippleDrawable can isolate its layers.
        first.mutate();
        LiveStackFill lease = new LiveStackFill();
        Drawable original = first.getDrawable(0);
        ColorFilter previous = new PorterDuffColorFilter(0xff112233, PorterDuff.Mode.SRC_ATOP);
        original.setColorFilter(previous);
        lease.sync(frame);
        assertNotSame(previous, original.getColorFilter());
        frame.setBackground(shape(0xff123456));
        lease.sync(frame);
        assertSame(original, first.getDrawable(0));
        assertSame(previous, original.getColorFilter());
        assertTrue(frame.getBackground() instanceof GradientDrawable);
    }

    @Test public void twoCardLeasesDoNotRestoreEachOthersDecoration() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout first = new FrameLayout(a), second = new FrameLayout(a);
        RippleDrawable ra = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xffffffff), null);
        RippleDrawable rb = new RippleDrawable(ColorStateList.valueOf(0),
                shape(0xfa3a3a3d), null);
        first.setBackground(ra);
        second.setBackground(rb);
        LiveStackFill aLease = new LiveStackFill(), bLease = new LiveStackFill();
        aLease.sync(first);
        bLease.sync(second);
        assertNotNull(ra.getDrawable(0).getColorFilter());
        assertNotNull(rb.getDrawable(0).getColorFilter());
        aLease.restore();
        assertNull(ra.getDrawable(0).getColorFilter());
        assertNotNull("second card remains muted while swipe is active",
                rb.getDrawable(0).getColorFilter());
        bLease.restore();
        assertNull(rb.getDrawable(0).getColorFilter());
    }

    @Test public void unrecognizedFillRemainsNative() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout frame = new FrameLayout(a);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x55ffffff),
                shape(0xff1144aa), null);
        frame.setBackground(ripple);
        LiveStackFill lease = new LiveStackFill();
        lease.sync(frame);
        assertNull(ripple.getDrawable(0).getColorFilter());
        assertEquals(255, ripple.getDrawable(0).getAlpha());
        lease.restore();
    }
}

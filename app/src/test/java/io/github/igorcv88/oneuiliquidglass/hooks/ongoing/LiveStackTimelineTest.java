package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The temporary timeline is observational: nothing it reads may rewrite a native drawable. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveStackTimelineTest {
    private static GradientDrawable shape(int argb) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(argb);
        d.setCornerRadius(40f);
        return d;
    }

    @Test public void rippleLayersExposeMaskFiltersAndBothNativeColoursWithoutMutation() {
        GradientDrawable white = shape(0xffffffff);
        GradientDrawable mask = shape(0xffffffff);
        GradientDrawable grey = shape(0xfa3a3a3d);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x22ffffff),
                white, mask);
        ripple.addLayer(grey);
        ripple.setBounds(0, 0, 500, 160);
        Drawable first = ripple.getDrawable(0);
        Drawable second = ripple.getDrawable(1);
        Drawable third = ripple.getDrawable(2);
        PorterDuffColorFilter filter = new PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN);
        third.setColorFilter(filter);

        String report = LiveStackTimeline.drawable(ripple);
        assertTrue(report, report.contains("rippleLayers=3"));
        assertTrue(report, report.contains("mask=true"));
        assertTrue(report, report.contains("fill=ffffffff"));
        assertTrue(report, report.contains("fill=fa3a3a3d"));
        assertTrue(report, report.contains("PorterDuffColorFilter"));
        assertSame(first, ripple.getDrawable(0));
        assertSame(second, ripple.getDrawable(1));
        assertSame(third, ripple.getDrawable(2));
        assertSame(filter, third.getColorFilter());
        assertEquals(255, first.getAlpha());
        assertEquals(255, third.getAlpha());
    }

    @Test public void viewSnapshotDoesNotChangeClippingChildrenOrBackground() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout card = new FrameLayout(activity);
        card.setClipToOutline(true);
        card.setElevation(60f);
        card.setTranslationZ(4f);
        TextView action = new TextView(activity);
        action.setText("private content must never enter the log");
        card.addView(action);
        GradientDrawable shape = shape(0xfa3a3a3d);
        card.setBackground(shape);
        card.layout(0, 0, 420, 160);
        String report = LiveStackTimeline.describe(card, "stack_pip_layout", shape);
        assertTrue(report, report.contains("res=stack_pip_layout"));
        assertTrue(report, report.contains("elev=60.0"));
        assertTrue(report, report.contains("clip=true"));
        assertTrue(report, report.contains("fill=fa3a3a3d"));
        assertFalse(report.contains("private content"));
        assertSame(shape, card.getBackground());
        assertSame(action, card.getChildAt(0));
        assertEquals(60f, card.getElevation(), 0f);
        assertEquals(4f, card.getTranslationZ(), 0f);
        assertTrue(card.getClipToOutline());
    }

    @Test public void unknownAndEmptyLayersRemainVisibleToTelemetryWithoutChanges() {
        Drawable unknown = new android.graphics.drawable.ColorDrawable(0xff113355);
        assertTrue(LiveStackTimeline.drawable(unknown).contains("ColorDrawable"));
        assertEquals("null", LiveStackTimeline.drawable(null));
        assertEquals(255, unknown.getAlpha());
    }
}

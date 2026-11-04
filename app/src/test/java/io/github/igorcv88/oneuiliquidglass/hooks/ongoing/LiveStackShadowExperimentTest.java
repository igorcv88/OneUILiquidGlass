package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** E2 / option A: only the three named indicator views change, and every change is restored exactly. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiveStackShadowExperimentTest {
    private Activity activity;
    private CardStackView stack;
    private FrameLayout frame;
    private View shadow1, shadow2, unrelated;
    private RippleDrawable ripple1, ripple2;
    private final Map<View, String> names = new IdentityHashMap<>();
    private final AtomicInteger mode = new AtomicInteger();
    private LiveStackShadowExperiment experiment;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        stack = new CardStackView(activity);
        stack.addView(new View(activity), new FrameLayout.LayoutParams(1340, 438));
        frame = new FrameLayout(activity);
        frame.setClipChildren(false);
        shadow1 = indicator(0.92f, -195f);
        shadow2 = indicator(0.84f, -171f);
        ripple1 = (RippleDrawable) shadow1.getBackground();
        ripple2 = (RippleDrawable) shadow2.getBackground();
        frame.addView(shadow1, new FrameLayout.LayoutParams(1340, 216));
        frame.addView(shadow2, new FrameLayout.LayoutParams(1340, 216));
        unrelated = new View(activity);
        unrelated.setBackground(new GradientDrawable());
        layout.addView(stack, new LinearLayout.LayoutParams(1340, 438));
        layout.addView(frame, new LinearLayout.LayoutParams(1340, 216));
        layout.addView(unrelated, new LinearLayout.LayoutParams(1340, 10));
        activity.setContentView(layout);
        layout.measure(View.MeasureSpec.makeMeasureSpec(1340, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 1340, 3120);
        names.put(frame, LiveStackShadowExperiment.FRAME);
        names.put(shadow1, LiveStackShadowExperiment.SHADOW1);
        names.put(shadow2, LiveStackShadowExperiment.SHADOW2);
        experiment = new LiveStackShadowExperiment(v -> names.getOrDefault(v, "-"), mode::get);
    }

    private View indicator(float scale, float ty) {
        View v = new View(activity);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        GradientDrawable content = new GradientDrawable();
        content.setColor(0xfa3a3a3d);
        content.setCornerRadius(108f);
        RippleDrawable r = new RippleDrawable(ColorStateList.valueOf(0x33ffffff), content, mask);
        v.setBackground(r);
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setTranslationY(ty);
        v.setAlpha(scale > 0.9f ? 0.5f : 0.3f);
        return v;
    }

    private static Drawable content(View v) {
        return LiveStackShadowExperiment.contentLayer((RippleDrawable) v.getBackground());
    }

    /** By identity: PorterDuffColorFilter.getColor() is not public SDK API. */
    private static boolean filterIs(Drawable d, ColorFilter expected) {
        return d.getColorFilter() == expected;
    }

    @Test public void modeZeroTouchesNothing() {
        mode.set(0);
        experiment.sync(stack, true);
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
        assertNull(frame.getClipBounds());
        assertNull(shadow1.getClipBounds());
        assertEquals(LiveStackShadowExperiment.MODE_OFF, experiment.mode());
    }

    @Test public void attributionTintsOnlyBothContentLayersAndRestores() {
        mode.set(LiveStackShadowExperiment.MODE_TINT);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.MAGENTA));
        assertTrue(filterIs(content(shadow2), LiveStackShadowExperiment.MAGENTA));
        // Mask, alpha, visibility, transform and the background instance stay native.
        assertSame(ripple1, shadow1.getBackground());
        assertNull(ripple1.getDrawable(1).getColorFilter());
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        assertEquals(View.VISIBLE, shadow1.getVisibility());
        assertEquals(-195f, shadow1.getTranslationY(), 0f);
        assertNull(unrelated.getBackground().getColorFilter());

        experiment.sync(stack, false);
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
    }

    @Test public void eachSuppressionModeIsolatesOneIndicator() {
        mode.set(LiveStackShadowExperiment.MODE_HIDE_1);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
        assertNull(content(shadow2).getColorFilter());

        mode.set(LiveStackShadowExperiment.MODE_HIDE_2);
        experiment.sync(stack, true);
        assertNull(content(shadow1).getColorFilter());
        assertTrue(filterIs(content(shadow2), LiveStackShadowExperiment.CLEAR));

        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
        assertTrue(filterIs(content(shadow2), LiveStackShadowExperiment.CLEAR));

        mode.set(LiveStackShadowExperiment.MODE_OFF);
        experiment.sync(stack, true);
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
    }

    @Test public void nativeFilterIsRestoredExactly() {
        ColorFilter samsung = new PorterDuffColorFilter(0x80123456, PorterDuff.Mode.SRC_ATOP);
        ripple1.mutate();
        content(shadow1).setColorFilter(samsung);
        mode.set(LiveStackShadowExperiment.MODE_HIDE_1);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
        experiment.restore("test");
        assertSame(samsung, content(shadow1).getColorFilter());
    }

    @Test public void frameClipKeepsOnlyItsOwnBoundsAndRestoresNativeClip() {
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 0, frame.getWidth(), frame.getHeight()), frame.getClipBounds());
        assertNull(shadow1.getClipBounds());
        assertNull(content(shadow1).getColorFilter());
        experiment.sync(stack, false);
        assertNull(frame.getClipBounds());

        Rect samsung = new Rect(1, 2, 3, 4);
        frame.setClipBounds(samsung);
        experiment.sync(stack, true);
        experiment.restore("test");
        assertEquals(samsung, frame.getClipBounds());
    }

    @Test public void perViewClipMatchesTheMeasuredDzifGeometry() {
        // DZIF: top 0, translationY -195, pivot 108, scale 0.92 -> 0.92*y > 186.36 -> y >= 203.
        assertEquals(new Rect(0, 203, 1340, 216), LiveStackShadowExperiment.belowParentTop(shadow1));
        // translationY -171, scale 0.84 -> 0.84*y > 153.72 -> y >= 183.
        assertEquals(new Rect(0, 183, 1340, 216), LiveStackShadowExperiment.belowParentTop(shadow2));

        mode.set(LiveStackShadowExperiment.MODE_CLIP_VIEWS);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 203, 1340, 216), shadow1.getClipBounds());
        assertEquals(new Rect(0, 183, 1340, 216), shadow2.getClipBounds());
        assertNull(frame.getClipBounds());
        experiment.sync(stack, false);
        assertNull(shadow1.getClipBounds());
        assertNull(shadow2.getClipBounds());
    }

    @Test public void unnamedIndicatorsAreNeverTouched() {
        names.clear();
        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
        assertEquals(LiveStackShadowExperiment.MODE_OFF, experiment.mode());
    }

    @Test public void detachRestores() {
        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
        ((LinearLayout) frame.getParent()).removeView(frame);
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
    }

    @Test public void degenerateGeometryKeepsNativeClip() {
        View empty = new View(activity);
        assertNull(LiveStackShadowExperiment.belowParentTop(empty));
        assertNull(LiveStackShadowExperiment.belowParentTop(null));
    }
}

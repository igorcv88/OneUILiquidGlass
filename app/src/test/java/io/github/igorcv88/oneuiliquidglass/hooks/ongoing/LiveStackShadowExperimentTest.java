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
    private CountingFrame frame;
    private LinearLayout layout;
    private View shadow1, shadow2, unrelated;
    private RippleDrawable ripple1, ripple2;
    private final Map<View, String> names = new IdentityHashMap<>();
    private final AtomicInteger mode = new AtomicInteger();
    private LiveStackShadowExperiment experiment;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        stack = new CardStackView(activity);
        stack.addView(new View(activity), new FrameLayout.LayoutParams(1340, 438));
        frame = newFrame();
        shadow1 = frame.getChildAt(0);
        shadow2 = frame.getChildAt(1);
        ripple1 = (RippleDrawable) shadow1.getBackground();
        ripple2 = (RippleDrawable) shadow2.getBackground();
        unrelated = new View(activity);
        unrelated.setBackground(new GradientDrawable());
        layout.addView(stack, new LinearLayout.LayoutParams(1340, 438));
        layout.addView(frame, new LinearLayout.LayoutParams(1340, 216));
        layout.addView(unrelated, new LinearLayout.LayoutParams(1340, 10));
        activity.setContentView(layout);
        layoutAll();
        experiment = new LiveStackShadowExperiment(v -> names.getOrDefault(v, "-"), mode::get);
    }

    /** Counts clip writes, to prove a steady frame re-applies nothing. */
    static final class CountingFrame extends FrameLayout {
        int clipWrites;
        CountingFrame(Activity a) { super(a); }
        @Override public void setClipBounds(Rect r) { clipWrites++; super.setClipBounds(r); }
    }

    /** A named frame with both named indicators, as DZIF inflates it; not laid out (0x0). */
    private CountingFrame newFrame() {
        CountingFrame f = new CountingFrame(activity);
        f.setClipChildren(false);
        View a = indicator(0.92f, -195f), b = indicator(0.84f, -171f);
        f.addView(a, new FrameLayout.LayoutParams(1340, 216));
        f.addView(b, new FrameLayout.LayoutParams(1340, 216));
        names.put(f, LiveStackShadowExperiment.FRAME);
        names.put(a, LiveStackShadowExperiment.SHADOW1);
        names.put(b, LiveStackShadowExperiment.SHADOW2);
        return f;
    }

    private void layoutAll() {
        layout.measure(View.MeasureSpec.makeMeasureSpec(1340, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY));
        layout.layout(0, 0, 1340, 3120);
    }

    private void preDraw() { stack.getViewTreeObserver().dispatchOnPreDraw(); }

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
        preDraw();
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
        assertNull(frame.getClipBounds());
    }

    @Test public void removedFrameIsRestoredOnTheNextFrame() {
        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
        layout.removeView(frame);
        preDraw();
        assertNull(content(shadow1).getColorFilter());
        assertNull(content(shadow2).getColorFilter());
    }

    @Test public void frameClipWaitsForLayoutThenPersists() {
        // The DZIF frame can be 0x0 when the first writer arrives.
        layout.removeView(frame);
        CountingFrame fresh = newFrame();
        layout.addView(fresh, 1, new LinearLayout.LayoutParams(1340, 216));
        assertEquals(0, fresh.getWidth());
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertNull("no clip before layout", fresh.getClipBounds());
        assertEquals(0, fresh.clipWrites);

        layoutAll();
        preDraw();
        assertEquals(new Rect(0, 0, 1340, 216), fresh.getClipBounds());
        assertEquals(1, fresh.clipWrites);

        // Steady frames: no new clip write.
        for (int i = 0; i < 5; i++) preDraw();
        assertEquals(1, fresh.clipWrites);
        assertEquals(new Rect(0, 0, 1340, 216), fresh.getClipBounds());
    }

    @Test public void recreatedFrameGetsTheClipAndTheOldOneIsRestored() {
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 0, 1340, 216), frame.getClipBounds());

        CountingFrame old = frame;
        layout.removeView(old);
        CountingFrame fresh = newFrame();
        layout.addView(fresh, 1, new LinearLayout.LayoutParams(1340, 216));
        preDraw();
        assertNull("old frame restored", old.getClipBounds());
        assertNull("new frame waits for layout", fresh.getClipBounds());
        layoutAll();
        preDraw();
        assertEquals(new Rect(0, 0, 1340, 216), fresh.getClipBounds());
    }

    @Test public void stackDetachSuspendsAndReattachResumes() {
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 0, 1340, 216), frame.getClipBounds());

        activity.setContentView(new FrameLayout(activity));
        assertFalse(stack.isAttachedToWindow());
        assertNull("restored while detached", frame.getClipBounds());
        assertEquals(LiveStackShadowExperiment.MODE_CLIP_FRAME, experiment.mode());

        activity.setContentView(layout);
        layoutAll();
        preDraw();
        assertEquals(new Rect(0, 0, 1340, 216), frame.getClipBounds());
    }

    @Test public void propertyZeroRestoresWithoutANewWriter() {
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 0, 1340, 216), frame.getClipBounds());
        mode.set(LiveStackShadowExperiment.MODE_OFF);
        preDraw();
        assertNull(frame.getClipBounds());
        assertEquals(LiveStackShadowExperiment.MODE_OFF, experiment.mode());
        // Disarmed: later frames do nothing.
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        preDraw();
        assertNull(frame.getClipBounds());
    }

    @Test public void modeChangeOnAFrameSwapsLeasesExactly() {
        mode.set(LiveStackShadowExperiment.MODE_CLIP_FRAME);
        experiment.sync(stack, true);
        assertEquals(new Rect(0, 0, 1340, 216), frame.getClipBounds());
        mode.set(LiveStackShadowExperiment.MODE_TINT);
        preDraw();
        assertNull(frame.getClipBounds());
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.MAGENTA));
        assertTrue(filterIs(content(shadow2), LiveStackShadowExperiment.MAGENTA));
    }

    @Test public void replacedIndicatorDrawableIsRestoredAndTheNewOneFiltered() {
        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        Drawable oldContent = content(shadow1);
        assertTrue(filterIs(oldContent, LiveStackShadowExperiment.CLEAR));
        View replacement = indicator(0.92f, -195f);
        shadow1.setBackground(replacement.getBackground());
        preDraw();
        assertNull("old drawable restored", oldContent.getColorFilter());
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));
    }

    // ---- modes 7-9: direct suppression, DZIF show sequence ----------------------------------

    /** What Samsung does when it shows the indicator: VISIBLE, blur background, alpha fade start. */
    private static CardStackView.NativeBackgroundBlurDrawable show(View indicator, float alpha) {
        CardStackView.NativeBackgroundBlurDrawable blur = new CardStackView.NativeBackgroundBlurDrawable();
        // Truncated like an int alpha written from a float fade (0.5f -> 127), not rounded (128).
        blur.setAlpha((int) (alpha * 255));
        indicator.setBackground(blur);
        indicator.setAlpha(alpha);
        return blur;
    }

    @Test public void filterModeReportsLossOfEligibilityWhenSamsungInstallsTheBlurRegion() {
        frame.setVisibility(View.GONE);
        mode.set(LiveStackShadowExperiment.MODE_HIDE_BOTH);
        experiment.sync(stack, true);
        assertTrue(filterIs(content(shadow1), LiveStackShadowExperiment.CLEAR));

        frame.setVisibility(View.VISIBLE);
        show(shadow1, 0.03f);
        show(shadow2, 0.03f);
        preDraw();
        assertFalse(experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_FILTER_REJECTED, experiment.pending());
        assertEquals(LiveStackShadowExperiment.REJECT_NOT_RIPPLE, experiment.filterReject(1));
        assertEquals(LiveStackShadowExperiment.REJECT_NOT_RIPPLE, experiment.filterReject(2));
        // The old ripple content is restored even though the view no longer draws it.
        assertNull(ripple1.getDrawable(0).getColorFilter());
    }

    @Test public void suppressAllSurvivesGoneToVisibleAndSamsungFadeThenRestoresExactly() {
        frame.setVisibility(View.GONE);
        mode.set(LiveStackShadowExperiment.MODE_SUPPRESS_ALL);
        experiment.sync(stack, true);
        assertFalse("GONE is never a success", experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_NOT_SHOWN, experiment.pending());

        frame.setVisibility(View.VISIBLE);
        CardStackView.NativeBackgroundBlurDrawable b1 = show(shadow1, 0.03f), b2 = show(shadow2, 0.01f);
        preDraw();
        assertTrue(experiment.effective());
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(0f, shadow2.getTransitionAlpha(), 0f);
        assertEquals(0, b1.getAlpha());
        assertEquals(0, b2.getAlpha());

        // Samsung's fade keeps writing both alphas: each write is undone before the frame draws.
        shadow1.setAlpha(0.5f); b1.setAlpha(127);
        shadow2.setAlpha(0.3f); b2.setAlpha(76);
        preDraw();
        assertTrue(experiment.effective());
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(0, b1.getAlpha());

        // Steady: no further writes.
        preDraw();
        assertTrue(experiment.effective());

        // Samsung's own alpha was never overwritten.
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        mode.set(LiveStackShadowExperiment.MODE_OFF);
        preDraw();
        assertEquals(1f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(1f, shadow2.getTransitionAlpha(), 0f);
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        assertEquals(0.3f, shadow2.getAlpha(), 0f);
        assertEquals(127, b1.getAlpha());
        assertEquals(76, b2.getAlpha());
    }

    @Test public void alphaOnlyLeavesTheRegionAndRegionOnlyLeavesTheView() {
        CardStackView.NativeBackgroundBlurDrawable b1 = show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_ALPHA);
        experiment.sync(stack, true);
        assertTrue(experiment.effective());
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(127, b1.getAlpha());

        mode.set(LiveStackShadowExperiment.MODE_REGION);
        preDraw();
        assertTrue(experiment.effective());
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        assertEquals(0, b1.getAlpha());

        experiment.restore("test");
        assertEquals(127, b1.getAlpha());
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
    }

    @Test public void regionModeIsPendingWhileTheIndicatorStillHasTheRipple() {
        mode.set(LiveStackShadowExperiment.MODE_REGION);
        experiment.sync(stack, true);
        assertFalse(experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_REGION_NOT_BLUR, experiment.pending());
    }

    @Test public void lateLayoutIsPendingUntilTheIndicatorsHaveASize() {
        layout.removeView(frame);
        CountingFrame fresh = newFrame();
        layout.addView(fresh, 1, new LinearLayout.LayoutParams(1340, 216));
        View a = fresh.getChildAt(0), b = fresh.getChildAt(1);
        show(a, 0.5f);
        show(b, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_SUPPRESS_ALL);
        experiment.sync(stack, true);
        assertFalse(experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_NOT_LAID_OUT, experiment.pending());
        layoutAll();
        preDraw();
        assertTrue(experiment.effective());
        assertEquals(0f, a.getTransitionAlpha(), 0f);
    }

    @Test public void replacedBlurBackgroundIsRestoredAndTheNewOneSuppressed() {
        CardStackView.NativeBackgroundBlurDrawable old = show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_SUPPRESS_ALL);
        experiment.sync(stack, true);
        assertEquals(0, old.getAlpha());
        CardStackView.NativeBackgroundBlurDrawable next = new CardStackView.NativeBackgroundBlurDrawable();
        next.setAlpha(127);
        shadow1.setBackground(next);
        preDraw();
        assertEquals("old region restored", 127, old.getAlpha());
        assertEquals(0, next.getAlpha());
        assertTrue(experiment.effective());
    }

    @Test public void rebuiltIndicatorsGetSuppressedAndTheOldOnesRestored() {
        show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_ALPHA);
        experiment.sync(stack, true);
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        View oldA = shadow1;
        layout.removeView(frame);
        CountingFrame fresh = newFrame();
        layout.addView(fresh, 1, new LinearLayout.LayoutParams(1340, 216));
        layoutAll();
        preDraw();
        assertEquals("old indicator restored", 1f, oldA.getTransitionAlpha(), 0f);
        assertEquals(0.5f, oldA.getAlpha(), 0f);
        assertEquals(0f, fresh.getChildAt(0).getTransitionAlpha(), 0f);
        assertEquals(0f, fresh.getChildAt(1).getTransitionAlpha(), 0f);
        assertTrue(experiment.effective());
    }

    @Test public void stackDetachRestoresAlphaAndReattachSuppressesAgain() {
        CardStackView.NativeBackgroundBlurDrawable b1 = show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_SUPPRESS_ALL);
        experiment.sync(stack, true);
        assertTrue(experiment.effective());
        activity.setContentView(new FrameLayout(activity));
        assertEquals(1f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        assertEquals(127, b1.getAlpha());
        assertFalse(experiment.effective());
        activity.setContentView(layout);
        layoutAll();
        preDraw();
        assertTrue(experiment.effective());
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(0, b1.getAlpha());
    }

    @Test public void losingGlassRestoresAlpha() {
        show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_ALPHA);
        experiment.sync(stack, true);
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        experiment.sync(stack, false);
        assertEquals(1f, shadow1.getTransitionAlpha(), 0f);
        assertEquals(1f, shadow2.getTransitionAlpha(), 0f);
        assertEquals(0.5f, shadow1.getAlpha(), 0f);
        assertEquals(0.3f, shadow2.getAlpha(), 0f);
    }

    @Test public void nativeWriteOfZeroDuringSuppressionSurvivesTheRestore() {
        show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        mode.set(LiveStackShadowExperiment.MODE_ALPHA);
        experiment.sync(stack, true);
        assertEquals(0f, shadow1.getTransitionAlpha(), 0f);
        // End of Samsung's fade-out while we suppress.
        shadow1.setAlpha(0f);
        preDraw();
        experiment.restore("test");
        assertEquals("native 0 kept", 0f, shadow1.getAlpha(), 0f);
        assertEquals(1f, shadow1.getTransitionAlpha(), 0f);
    }

    /** DZIF with two cards: Samsung shows only shadow1; shadow2 stays GONE at 0x0. */
    @Test public void regionModeIsEffectiveWhenOnlyTheFirstIndicatorIsShown() {
        CardStackView.NativeBackgroundBlurDrawable b1 = show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        shadow2.setVisibility(View.GONE);
        mode.set(LiveStackShadowExperiment.MODE_REGION);
        experiment.sync(stack, true);
        assertTrue(experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_NONE, experiment.pending());
        assertEquals(0, b1.getAlpha());
        assertEquals("view alpha untouched in mode 9", 0.5f, shadow1.getAlpha(), 0f);
        assertEquals(1f, shadow1.getTransitionAlpha(), 0f);
    }

    @Test public void regionModeIsNotEffectiveWhileNoIndicatorIsShown() {
        show(shadow1, 0.5f);
        show(shadow2, 0.3f);
        frame.setVisibility(View.GONE);
        mode.set(LiveStackShadowExperiment.MODE_REGION);
        experiment.sync(stack, true);
        assertFalse(experiment.effective());
        assertEquals(LiveStackShadowExperiment.PENDING_NOT_SHOWN, experiment.pending());
    }

    @Test public void unsetPropertyDefaultsToTheRegionFix() {
        // Robolectric's SystemProperties returns "" for an unset key.
        assertEquals(LiveStackShadowExperiment.MODE_REGION, LiveStackShadowExperiment.propertyMode());
    }

    @Test public void degenerateGeometryKeepsNativeClip() {
        View empty = new View(activity);
        assertNull(LiveStackShadowExperiment.belowParentTop(empty));
        assertNull(LiveStackShadowExperiment.belowParentTop(null));
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Material and geometry fidelity of the wrapper, and the classification rules. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveGlassDrawableTest {
    @Test public void tintKeepsRgbAndOnlyScalesAlpha() {
        assertEquals(0x851e6bff, LiveGlassDrawable.glassTint(0xff1e6bff, 0.52f, 0));
        assertEquals(0x401e6bff, LiveGlassDrawable.glassTint(0x801e6bff, 0.5f, 0));
        assertEquals(0x29ffffff, LiveGlassDrawable.glassTint(null, 0.5f, 0x29ffffff));
        assertEquals(0x001e6bff, LiveGlassDrawable.glassTint(0xff1e6bff, -1f, 0));
    }

    @Test public void radiiAreClampedLikeARoundRect() {
        float[] out = new float[8];
        LiveGlassDrawable.clampRadii(NativeShape.uniform(500f), 300f, 80f, out);
        for (float r : out) assertEquals(40f, r, 0f);
        LiveGlassDrawable.clampRadii(NativeShape.uniform(12f), 300f, 80f, out);
        assertEquals(12f, out[3], 0f);
    }

    @Test public void unchangedRadiiDoNotInvalidateTheDrawable() {
        LiveGlassDrawable d = new LiveGlassDrawable(new ColorDrawable(0xff123456), NativeShape.uniform(16f),
                0xff123456, LiveSurface.NOWBAR.spec, 1f);
        int[] invalidations = {0};
        d.setCallback(new android.graphics.drawable.Drawable.Callback() {
            @Override public void invalidateDrawable(android.graphics.drawable.Drawable who) { invalidations[0]++; }
            @Override public void scheduleDrawable(android.graphics.drawable.Drawable who, Runnable what, long when) { }
            @Override public void unscheduleDrawable(android.graphics.drawable.Drawable who, Runnable what) { }
        });
        for (int i = 0; i < 60; i++) d.setRadii(NativeShape.uniform(16f));
        assertEquals("the Now Bar rebinds frequently but its shape rarely changes", 0, invalidations[0]);
        d.setRadii(NativeShape.uniform(24f));
        assertEquals(1, invalidations[0]);
    }

    /** Padding, minimum and intrinsic size are the native ones: the host measures exactly as before. */
    @Test public void geometryIsDelegatedToTheNativeDrawable() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(30f);
        g.setPadding(7, 3, 9, 5);
        g.setSize(140, 48);
        LiveGlassDrawable d = new LiveGlassDrawable(g, NativeShape.uniform(30f), 0xff000000, LiveSurface.CHIP.spec, 3f);
        d.setNative(g);
        Rect p = new Rect();
        assertTrue(d.getPadding(p));
        assertEquals(new Rect(7, 3, 9, 5), p);
        assertEquals(140, d.getIntrinsicWidth());
        assertEquals(48, d.getMinimumHeight());
    }

    @Test public void nativeModeDrawsTheNativeDrawableAndGlassModeTheRegion() throws Exception {
        int[] nativeDraws = {0};
        ColorDrawable nativeBg = new ColorDrawable(0xff123456) {
            @Override public void draw(Canvas c) { nativeDraws[0]++; super.draw(c); }
        };
        LiveGlassDrawable d = new LiveGlassDrawable(nativeBg, NativeShape.uniform(20f), 0xff123456, LiveSurface.TOAST.spec, 2f);
        d.setNative(nativeBg);
        d.setBounds(0, 0, 200, 60);
        Canvas c = new Canvas(android.graphics.Bitmap.createBitmap(200, 60, android.graphics.Bitmap.Config.ARGB_8888));
        d.draw(c);
        assertEquals("no region: native", 1, nativeDraws[0]);
        FakeBlurDrawable region = new FakeBlurDrawable();
        d.setBlur(BackgroundBlurBridge.of(region));
        d.draw(c);
        assertEquals(1, nativeDraws[0]);
        assertEquals(1, region.draws);
        assertEquals(LiveSurface.TOAST.spec.blurPx, region.radius);
        assertEquals(LiveGlassDrawable.glassTint(0xff123456, LiveSurface.TOAST.spec.tintAlpha, 0), region.color);
        assertEquals(20f, region.corners[0], 0f);
        d.setGlass(false); // doze / policy
        d.draw(c);
        assertEquals(2, nativeDraws[0]);
    }

    @Test public void outlineDropsTheShadowOnlyUnderGlass() throws Exception {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(16f);
        LiveGlassDrawable d = new LiveGlassDrawable(g, NativeShape.uniform(16f), null, LiveSurface.CARD.spec, 1f);
        d.setNative(g);
        d.setBounds(0, 0, 100, 50);
        d.setBlur(BackgroundBlurBridge.of(new FakeBlurDrawable()));
        Outline o = new Outline();
        d.getOutline(o);
        assertEquals(0f, o.getAlpha(), 0f);
        assertEquals(16f, o.getRadius(), 0f);
    }

    @Test public void regionFollowsAncestorAlpha() throws Exception {
        LiveGlassDrawable d = new LiveGlassDrawable(new ColorDrawable(), NativeShape.uniform(4f), null, LiveSurface.CHIP.spec, 1f);
        FakeBlurDrawable region = new FakeBlurDrawable();
        d.setBlur(BackgroundBlurBridge.of(region));
        assertTrue(d.setRegionFade(0.5f));
        assertEquals(128, region.alpha, 1);
        assertFalse("unchanged alpha: no work", d.setRegionFade(0.501f));
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(a);
        View child = new View(a);
        parent.addView(child);
        parent.setAlpha(0.5f);
        child.setAlpha(0.5f);
        assertEquals(0.25f, LiveGlass.accumulatedAlpha(child), 0.001f);
        parent.setVisibility(View.INVISIBLE);
        assertEquals(0f, LiveGlass.accumulatedAlpha(child), 0f);
    }

    @Test public void shapeIsReadFromTheNativeDrawableOrHostNeverGuessed() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(22f);
        assertEquals(22f, NativeShape.radii(new InsetDrawable(g, 4), null)[0], 0f);
        assertEquals(64f, NativeShape.radii(null, new FakeCardView(a))[0], 0f);
        assertNull(NativeShape.radii(new ColorDrawable(), new View(a)));
        assertTrue(NativeShape.interactive(new RippleDrawable(ColorStateList.valueOf(0), g, null)));
        assertTrue(NativeShape.cardLike(new FakeCardView(a)));
        assertFalse(NativeShape.cardLike(new FrameLayout(a)));
        g.setColor(0xff445566);
        assertEquals(Integer.valueOf(0xff445566), NativeShape.color(g, null));
        FakeCardView card = new FakeCardView(a);
        card.setCardBackgroundColor(0xff2266ee);
        assertEquals("CardView's own colour outranks its old ColorDrawable background",
                Integer.valueOf(0xff2266ee), NativeShape.color(card.getBackground(), card));
        GradientDrawable staleShape = new GradientDrawable();
        staleShape.setCornerRadius(64f);
        card.setBackground(staleShape);
        card.setRadius(24f);
        assertEquals("CardView clip radius outranks the replaced background radius",
                24f, NativeShape.radii(card.getBackground(), card)[0], 0f);
    }

    @Test public void rippleAndCardViewBackgroundsAreNeverReplaced() {
        Env env = new Env();
        LiveGlass glass = env.glass();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout clickable = new FrameLayout(a);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0), new GradientDrawable(), null);
        clickable.setBackground(ripple);
        assertEquals(LiveGlass.Outcome.NATIVE, glass.offer(clickable, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, o -> true));
        assertSame(ripple, clickable.getBackground());
        FakeCardView card = new FakeCardView(a);
        android.graphics.drawable.Drawable own = card.getBackground();
        assertEquals(LiveGlass.Outcome.NATIVE, glass.offer(card, LiveSurface.NOWBAR, new Object(), LiveEligibility.Verdict.OK, o -> true));
        assertSame(own, card.getBackground());
    }

    @Test public void eligibilityDeniesOnDoubt() {
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData d =
                new com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData("com.example", false);
        assertEquals(LiveEligibility.Verdict.OK, LiveEligibility.ongoing(d));
        d.mIsMediaOngoingData = true;
        assertEquals(LiveEligibility.Verdict.MEDIA_FLAG, LiveEligibility.ongoing(d));
        d.mIsMediaOngoingData = false; d.mPackageName = null;
        assertEquals(LiveEligibility.Verdict.NO_PACKAGE, LiveEligibility.ongoing(d));
        assertEquals(LiveEligibility.Verdict.NO_DATA, LiveEligibility.ongoing(new Object()));
        assertEquals(LiveEligibility.Verdict.NO_DATA, LiveEligibility.ongoing(null));
        assertEquals(LiveEligibility.Verdict.MEDIA_UNKNOWN, LiveEligibility.card(d, null));
        assertEquals(LiveEligibility.Verdict.MEDIA_VIEW, LiveEligibility.card(d, true));
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals(LiveEligibility.Verdict.WRONG_CLASS,
                LiveEligibility.nowBar(new com.samsung.android.app.facewidget.presentation.NowBarMediaCardView(a), new Object()));
        assertEquals(LiveEligibility.Verdict.MEDIA_UNKNOWN,
                LiveEligibility.nowBar(new com.samsung.android.app.facewidget.presentation.NowBarOngoingCardView(a), new Object()));
        LinearLayout toast = new LinearLayout(a);
        assertEquals(LiveEligibility.Verdict.NOT_TEXT, LiveEligibility.toast(toast));
        toast.addView(new TextView(a));
        assertEquals(LiveEligibility.Verdict.OK, LiveEligibility.toast(toast));
        assertEquals(LiveEligibility.Verdict.NOT_TEXT, LiveEligibility.toast(new TextView(a)));
    }
}

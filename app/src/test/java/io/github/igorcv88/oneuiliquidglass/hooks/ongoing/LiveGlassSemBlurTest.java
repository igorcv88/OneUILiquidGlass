package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Samsung's own blur (SemBlurInfo) leaves a host only while the glass is really drawn: before
 * attach, without cross-window blur, in doze, or when the region cannot be built, the host keeps
 * the native background and Samsung's blur, never neither.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveGlassSemBlurTest {
    private Env env;
    private LiveGlass glass;
    private Activity activity;
    private FrameLayout window;
    private GradientDrawable nativeBg;

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        window = new FrameLayout(activity);
        activity.setContentView(window);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    /** A card as Samsung leaves it: changeCardBg's shape, then setBlurBG's blur. */
    private View samsungCard() {
        View v = new FrameLayout(activity);
        nativeBg = new GradientDrawable();
        nativeBg.setCornerRadius(56f);
        nativeBg.setColor(0xff2050c0);
        v.setBackground(nativeBg);
        LiveGlass.Outcome o = glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        assertTrue(o == LiveGlass.Outcome.APPLIED);
        env.samsungBlur(v);
        return v;
    }
    private boolean onSamsungBlur(View v) { return env.held(v) != null && v.getBackground() instanceof Env.SamsungBlurDrawable; }

    @Test public void detachedHostKeepsSamsungBlurUntilAttach() {
        View v = samsungCard();
        assertEquals(LiveGlass.Outcome.NATIVE, glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true));
        assertTrue("not attached: Samsung's blur stays", onSamsungBlur(v));
        window.addView(v);
        LiveGlassDrawable g = (LiveGlassDrawable) v.getBackground();
        assertTrue(g.showsGlass());
        assertNull(env.held(v));
        assertSame(nativeBg, g.nativeDrawable());
    }

    @Test public void blurTurnedOffGivesSamsungBlurBackAndOnTakesItAgain() {
        View v = samsungCard();
        window.addView(v);
        glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        assertTrue(((LiveGlassDrawable) v.getBackground()).showsGlass());
        env.blurEnabled = false; // battery saver
        glass.syncAll();
        assertTrue("never a host without both blurs", onSamsungBlur(v));
        env.blurEnabled = true;
        glass.syncAll();
        LiveGlassDrawable g = (LiveGlassDrawable) v.getBackground();
        assertTrue(g.showsGlass());
        assertNull(env.held(v));
        assertSame("the shape under Samsung's blur is still the wrapped native", nativeBg, g.nativeDrawable());
    }

    @Test public void dozeAndDetachKeepSamsungBlur() {
        View v = samsungCard();
        window.addView(v);
        glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        env.glassAllowed = false;
        glass.syncAll();
        assertTrue(onSamsungBlur(v));
        env.glassAllowed = true;
        glass.syncAll();
        assertTrue(v.getBackground() instanceof LiveGlassDrawable);
        window.removeView(v);
        window.addView(v);
        assertTrue(((LiveGlassDrawable) v.getBackground()).showsGlass());
        env.blurEnabled = false;
        window.removeView(v);
        window.addView(v);
        assertTrue("reattached without blur: Samsung's again", onSamsungBlur(v));
    }

    @Test public void regionThatCannotBeBuiltLeavesSamsungBlurAlone() {
        View v = samsungCard();
        window.addView(v);
        glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        env.blurEnabled = false;
        glass.syncAll();
        assertTrue(onSamsungBlur(v));
        // Blur comes back, but the compositor region cannot be built on this firmware.
        env.failCreate = true;
        env.blurEnabled = true;
        glass.syncAll();
        assertTrue("Samsung's blur is never removed for a region that does not exist", onSamsungBlur(v));
        env.failCreate = false;
        glass.syncAll();
        assertTrue("no retry loop after a failed region", onSamsungBlur(v));
    }

    @Test public void writerReinstallingBlurWhileUnavailableIsLeftNative() {
        View v = samsungCard();
        window.addView(v);
        glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        env.glassAllowed = false;
        env.samsungBlur(v); // setBlurBG again
        assertEquals(LiveGlass.Outcome.NATIVE, glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true));
        assertTrue(onSamsungBlur(v));
    }

    @Test public void restoreReinstallsSamsungBlurExactly() {
        View v = samsungCard();
        window.addView(v);
        Object info = env.held(v);
        glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true);
        glass.forget(v, "test");
        assertSame(info, env.held(v));
        assertFalse(glass.tracked(v));
    }
}

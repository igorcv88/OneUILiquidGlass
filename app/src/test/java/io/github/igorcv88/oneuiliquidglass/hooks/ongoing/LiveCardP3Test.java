package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.P3ModeBus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * R5 6.2: the live card takes the pop-up's P3 edge selection. It reads the display mode once on
 * creation/attach and, only while it is a visible heads-up, follows the heads-up's shared watcher
 * (P3ModeBus) in place: no second poller, no card recreation.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveCardP3Test {
    /** Stands in for SystemUI's row: the walk matches the class name suffix. */
    public static class ExpandableNotificationRow extends FrameLayout {
        boolean headsUp;
        public ExpandableNotificationRow(Context c) { super(c); }
        public boolean isHeadsUpState() { return headsUp; }
    }

    private Env env;
    private LiveGlass glass;
    private FrameLayout window;
    private ExpandableNotificationRow row;
    private int starts;

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        window = new FrameLayout(activity);
        activity.setContentView(window);
        row = new ExpandableNotificationRow(activity);
        window.addView(row);
        P3ModeBus.setWatchStarter(() -> starts++);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void tearDown() { P3ModeBus.setWatchStarter(null); }

    private View card() {
        View v = new FrameLayout(row.getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(56f);
        bg.setColor(0xff2050c0);
        v.setBackground(bg);
        row.addView(v, 600, 200);
        assertEquals(LiveGlass.Outcome.APPLIED,
                glass.offer(v, LiveSurface.CARD, new Object(), LiveEligibility.Verdict.OK, x -> true));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((LiveGlassDrawable) v.getBackground()).showsGlass());
        return v;
    }

    @Test public void drawableSetterReportsChangeAndInvalidatesItsHost() {
        LiveGlassDrawable g = new LiveGlassDrawable(new FakeBlurDrawable(), new float[8], null, LiveSurface.CARD.spec, 3f);
        int[] invalidations = {0};
        g.setCallback(new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) { invalidations[0]++; }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {}
            @Override public void unscheduleDrawable(Drawable who, Runnable what) {}
        });
        assertTrue(g.setP3EdgeCompatibility(true));
        assertTrue(g.p3EdgeCompatibility());
        assertEquals(1, invalidations[0]);
        assertFalse("no change, no redraw", g.setP3EdgeCompatibility(true));
        assertEquals(1, invalidations[0]);
        assertTrue(g.setP3EdgeCompatibility(false));
        assertEquals(2, invalidations[0]);
    }

    @Test public void aVisibleHeadsUpCardFollowsTheSharedWatcherInPlace() {
        row.headsUp = true;
        View v = card();
        LiveGlassDrawable g = (LiveGlassDrawable) v.getBackground();
        assertFalse("unknown mode fails closed", g.p3EdgeCompatibility());
        assertSame("the card anchors the shared watcher", v, P3ModeBus.watchAnchor());
        assertTrue("it asked the existing watcher to run", starts > 0);
        P3ModeBus.publish(9);
        assertTrue(g.p3EdgeCompatibility());
        assertSame("updated in place, not recreated", g, v.getBackground());
        P3ModeBus.publish(7);
        assertFalse("back to SDR", g.p3EdgeCompatibility());
        P3ModeBus.publish(-1);
        assertFalse(g.p3EdgeCompatibility());
    }

    @Test public void aCardThatIsNotAPopupNeverKeepsTheWatcherAlive() {
        View v = card();
        LiveGlassDrawable g = (LiveGlassDrawable) v.getBackground();
        assertNull(P3ModeBus.watchAnchor());
        assertEquals(0, starts);
        P3ModeBus.publish(9);
        assertFalse("only watching clients take watcher samples", g.p3EdgeCompatibility());
        // Hidden or detached pop-up: no anchor either.
        row.headsUp = true;
        assertSame(v, P3ModeBus.watchAnchor());
        v.setVisibility(View.GONE);
        assertNull(P3ModeBus.watchAnchor());
        v.setVisibility(View.VISIBLE);
        row.removeView(v);
        assertNull(P3ModeBus.watchAnchor());
    }
}

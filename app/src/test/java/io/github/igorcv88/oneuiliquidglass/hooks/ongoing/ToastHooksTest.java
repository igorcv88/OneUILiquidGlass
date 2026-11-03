package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.android.systemui.toast.SystemUIToast;
import com.android.systemui.toast.ToastFactory;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** T1: glass from the factory on, region follows the native fade, native back when the window goes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class ToastHooksTest {
    private Env env;
    private LiveGlass glass;
    private ToastHooks hooks;
    private Activity activity;

    @Before public void setUp() throws Exception {
        env = new Env();
        glass = env.glass();
        hooks = new ToastHooks(glass);
        hooks.setGetView(SystemUIToast.class.getDeclaredMethod("getView"));
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }
    private SystemUIToast toast(String text) { return new ToastFactory().createToast(activity, activity, text, "com.android.systemui", 0, 0); }
    private FrameLayout window() {
        FrameLayout w = new FrameLayout(activity);
        activity.setContentView(w);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return w;
    }

    @Test public void copiedToastIsBornWithGlassAndRestoredOnHide() {
        SystemUIToast t = toast("Copied");
        View root = hooks.created(t);
        assertSame(t.getView(), root);
        assertTrue("wrapped before ToastPresenter.show", root.getBackground() instanceof LiveGlassDrawable);
        LiveGlassDrawable g = (LiveGlassDrawable) root.getBackground();
        Drawable nativeBg = g.nativeDrawable();
        FrameLayout w = window();
        w.addView(root);
        assertTrue(g.showsGlass());
        assertEquals(1, env.regions.size());
        // The native fade-in animates view alpha: the region follows on the next pre-draw.
        root.setAlpha(0.25f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(64, env.regions.get(0).alpha, 1);
        assertEquals(0x00303030, g.currentTint() & 0x00ffffff);
        w.removeView(root); // ToastPresenter.hide
        assertSame(nativeBg, root.getBackground());
        assertFalse(glass.tracked(root));
    }

    /** Created before the window exists; the region and its neutral curve arrive with the attach. */
    @Test public void regionArrivesWithTheWindowNotBefore() {
        View root = hooks.created(toast("Copied"));
        LiveGlassDrawable g = (LiveGlassDrawable) root.getBackground();
        assertFalse("no window yet: nothing translucent is drawn", g.showsGlass());
        assertTrue(env.regions.isEmpty());
        FrameLayout w = window();
        w.addView(root);
        assertTrue(g.showsGlass());
        assertTrue(g.blur().neutralCurve());
        assertFalse(g.blur().curveApplied());
        root.layout(0, 0, 400, 120);
        root.draw(new android.graphics.Canvas(android.graphics.Bitmap.createBitmap(400, 120, android.graphics.Bitmap.Config.ARGB_8888)));
        assertArrayEquals(new float[]{0, 0, 0, 255, 0, 255}, env.regions.get(0).curve, 0f);
        assertTrue(g.blur().curveApplied());
        w.removeView(root);
        assertEquals("region released with the window", 0, env.regions.get(0).radius);
    }

    @Test public void fourCopiesInARowEachGetTheirOwnGlass() {
        FrameLayout w = window();
        for (int i = 0; i < 4; i++) {
            View root = hooks.created(toast("Copied"));
            w.addView(root);
            assertTrue(((LiveGlassDrawable) root.getBackground()).showsGlass());
            w.removeView(root);
            assertTrue(root.getBackground() instanceof GradientDrawable);
        }
        assertTrue(glass.hosts(LiveSurface.TOAST).isEmpty());
    }

    @Test public void interactiveOrTextlessViewsStayNative() {
        LinearLayout custom = new LinearLayout(activity);
        custom.setBackground(new GradientDrawable());
        custom.addView(new Button(activity));
        hooks.created(new SystemUIToast(custom));
        assertFalse(custom.getBackground() instanceof LiveGlassDrawable);
        LinearLayout empty = new LinearLayout(activity);
        empty.setBackground(new GradientDrawable());
        hooks.created(new SystemUIToast(empty));
        assertFalse(empty.getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void toastGateOffLeavesItUntouched() {
        env.on.remove(LiveSurface.TOAST);
        View root = hooks.created(toast("Copied"));
        assertTrue(root.getBackground() instanceof GradientDrawable);
    }

    @Test public void regionUnavailableDrawsTheNativeToast() {
        env.failCreate = true;
        View root = hooks.created(toast("Copied"));
        FrameLayout w = window();
        w.addView(root);
        LiveGlassDrawable g = (LiveGlassDrawable) root.getBackground();
        assertFalse(g.showsGlass());
        env.failCreate = false;
        env.blurEnabled = false;
        View other = hooks.created(toast("Copied"));
        w.addView(other);
        assertFalse(((LiveGlassDrawable) other.getBackground()).showsGlass());
        assertTrue(env.regions.isEmpty());
    }

    @Test public void shapeOnAChildIsTheTarget() {
        LinearLayout root = new LinearLayout(activity);
        LinearLayout shape = new LinearLayout(activity);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(48f);
        shape.setBackground(d);
        shape.addView(new android.widget.TextView(activity));
        root.addView(shape);
        View target = hooks.created(new SystemUIToast(root));
        assertSame(shape, target);
        assertNull(root.getBackground());
        assertTrue(((ViewGroup) root).getChildAt(0).getBackground() instanceof LiveGlassDrawable);
    }
}

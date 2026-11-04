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
    public static class Presenter {
        final android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams();
        int flagsAtShow;
        Presenter() {
            params.type = android.view.WindowManager.LayoutParams.TYPE_TOAST;
            params.flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        public android.view.WindowManager.LayoutParams getLayoutParams() { return params; }
        public void show(View view, android.os.IBinder token, android.os.IBinder windowToken,
                         int duration, int gravity, int x, int y, float hm, float vm, Object callback, boolean removeAnimations) {
            flagsAtShow = params.flags;
        }
        public void hide(Object callback) { }
    }
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

    @Test public void presenterEnablesHardwareBeforeShowAndRestoresOnlyItsOwnBit() throws Exception {
        RecordingSite site = new RecordingSite();
        assertTrue(hooks.installPresenter(site, Presenter.class));
        Presenter presenter = new Presenter();
        int nativeFlags = presenter.params.flags;
        int hw = android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        View root = hooks.created(toast("Copied"));
        site.call(presenter, "show", root, null, null, 0, 0, 0, 0, 0f, 0f, null, false);
        assertEquals(nativeFlags | hw, presenter.flagsAtShow);
        presenter.params.flags |= android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        site.call(presenter, "hide", new Object());
        assertEquals(nativeFlags | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, presenter.params.flags);
        // The same presenter can subsequently show an unclassified/custom toast.
        site.call(presenter, "show", new View(activity), null, null, 0, 0, 0, 0, 0f, 0f, null, false);
        assertEquals(0, presenter.flagsAtShow & hw);
    }

    @Test public void windowFlagsAreScopedToEligibleToastsAndPreserveNativeHardware() {
        View root = hooks.created(toast("Copied"));
        Presenter presenter = new Presenter();
        int flags = presenter.params.flags;
        presenter.params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
        hooks.prepareWindow(root, presenter.params);
        assertEquals(flags, presenter.params.flags);
        presenter.params.type = android.view.WindowManager.LayoutParams.TYPE_TOAST;
        env.on.remove(LiveSurface.TOAST);
        hooks.prepareWindow(root, presenter.params);
        assertEquals(flags, presenter.params.flags);
        env.on.add(LiveSurface.TOAST);
        int hw = android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        presenter.params.flags |= hw;
        hooks.prepareWindow(root, presenter.params);
        hooks.restoreWindow(presenter.params);
        assertEquals("native hardware request is never removed", flags | hw, presenter.params.flags);
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
        g.setBounds(0, 0, root.getWidth(), root.getHeight());
        g.draw(new TestCanvas(400, 120));
        assertArrayEquals(new float[]{0, 0, 0, 255, 0, 255}, env.regions.get(0).curve, 0f);
        assertTrue(g.blur().curveApplied());
        w.removeView(root);
        assertEquals("region released with the window", 0, env.regions.get(0).radius);
    }

    @Test public void backgroundReplacedAfterAttachIsRecoveredBeforeDrawAndRestoredOnDetach() {
        View root = hooks.created(toast("Copied"));
        LiveGlassDrawable g = (LiveGlassDrawable) root.getBackground();
        FrameLayout w = window();
        w.addView(root);
        Drawable samsung = new Env.SamsungBlurDrawable();
        root.setBackground(samsung); // DZIF: replacement after factory/attach
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertSame(g, root.getBackground());
        root.setAlpha(1f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        g.setBounds(0, 0, 400, 120);
        g.draw(new TestCanvas(400, 120));
        assertTrue(env.regions.get(0).draws > 0);
        assertEquals(255, env.regions.get(0).alpha);
        w.removeView(root);
        assertSame(samsung, root.getBackground());
        assertFalse(glass.tracked(root));
    }

    @Test public void repeatedBackgroundReplacementHasABoundedNativeFallback() {
        View root = hooks.created(toast("Copied"));
        FrameLayout w = window();
        w.addView(root);
        Drawable latest = null;
        for (int i = 0; i < 4; i++) {
            latest = new Env.SamsungBlurDrawable();
            root.setBackground(latest);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
        assertSame(latest, root.getBackground());
        assertFalse(glass.tracked(root));
        assertEquals(0, env.regions.get(0).radius);
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


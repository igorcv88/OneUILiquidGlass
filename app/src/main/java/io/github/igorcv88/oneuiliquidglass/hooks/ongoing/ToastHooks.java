package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * T1: SystemUI text toasts (ClipboardListener's "Copied", any app's Toast.makeText). The view
 * comes from ToastFactory.createToast and is handed to ToastPresenter.show later; classifying and
 * wrapping it right after the factory means the TYPE_TOAST window is born with the glass and is
 * never shown opaque first. The toast's own animation (SystemUIToast), duration, callbacks,
 * offsets, accessibility and text are untouched; the region follows the view's alpha, and the
 * window's detach puts the native background back and forgets the view. No loop, no rebuild
 * workaround: a second reconstruction of the same toast was not proven.
 */
final class ToastHooks {
    static final String FACTORY = "com.android.systemui.toast.ToastFactory";
    static final String TOAST = "com.android.systemui.toast.SystemUIToast";
    static final String PRESENTER = "android.widget.ToastPresenter";

    private final LiveGlass glass;
    private Method getView;

    ToastHooks(LiveGlass glass) { this.glass = glass; }
    void setGetView(Method m) { getView = m; }

    /** Only the framework presenter for an already classified SystemUI text toast is changed. */
    boolean installPresenter(HookSite site, Class<?> presenter) {
        Method params = HookSite.exact(presenter, "getLayoutParams", WindowManager.LayoutParams.class);
        if (params == null) return false;
        boolean installed = false;
        for (Method m : presenter.getDeclaredMethods()) {
            Class<?>[] args = m.getParameterTypes();
            if (m.getName().equals("show") && m.getReturnType() == void.class
                    && (args.length == 10 || args.length == 11) && args[0] == View.class
                    && args[1] == android.os.IBinder.class && args[2] == android.os.IBinder.class) {
                installed |= site.hook(m, new HookSite.Callback() {
                    @Override public void before(HookSite.Param p) {
                        WindowManager.LayoutParams lp = layoutParams(params, p.thisObject);
                        if (lp != null) prepareWindow((View) p.args[0], lp);
                    }
                    @Override public void after(HookSite.Param p) {
                        if (p.threw) restoreWindow(layoutParams(params, p.thisObject));
                    }
                });
            } else if (m.getName().equals("hide") && m.getReturnType() == void.class && args.length == 1) {
                site.hook(m, new HookSite.Callback() {
                    @Override public void after(HookSite.Param p) { restoreWindow(layoutParams(params, p.thisObject)); }
                });
            }
        }
        return installed;
    }
    private static WindowManager.LayoutParams layoutParams(Method getter, Object presenter) {
        try { return (WindowManager.LayoutParams) getter.invoke(presenter); }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    private final WeakHashMap<WindowManager.LayoutParams, Boolean> accelerated = new WeakHashMap<>();
    /** Before ToastPresenter.show/addView: drawRenderNode cannot work on a software toast window. */
    void prepareWindow(View root, WindowManager.LayoutParams params) {
        if (params.type != WindowManager.LayoutParams.TYPE_TOAST) return;
        boolean eligible = glass.allows(LiveSurface.TOAST) && hasGlass(root);
        if (!eligible) {
            Probe.log("LIVE_RENDER_TOAST_WINDOW", "eligible=false windowType=" + params.type
                    + " hwFlag=" + ((params.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0));
            restoreWindow(params); return;
        }
        int bit = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if ((params.flags & bit) == 0) {
            accelerated.put(params, Boolean.TRUE);
            params.flags |= bit;
        }
        Probe.log("LIVE_RENDER_TOAST_WINDOW", "eligible=true windowType=" + params.type
                + " hwFlag=" + ((params.flags & bit) != 0)
                + " addedByModule=" + accelerated.containsKey(params));
    }
    void restoreWindow(WindowManager.LayoutParams params) {
        if (params != null && accelerated.remove(params) != null)
            params.flags &= ~WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }
    private boolean hasGlass(View view) {
        if (view == null) return false;
        if (glass.surfaceOf(view) == LiveSurface.TOAST && glass.applied(view)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) if (hasGlass(g.getChildAt(i))) return true;
        }
        return false;
    }

    /** After ToastFactory.createToast(...) with its SystemUIToast result. */
    View created(Object toast) {
        if (toast == null || getView == null) return null;
        View root;
        try {
            Object v = getView.invoke(toast);
            root = v instanceof View ? (View) v : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
        if (root == null) return null;
        LiveEligibility.Verdict verdict = LiveEligibility.toast(root);
        View target = target(root);
        if (target == null) target = root;
        glass.offer(target, LiveSurface.TOAST, toast, verdict, o -> true);
        return target;
    }

    /** The view drawing the rounded toast shape: the root, or the first container below it with a background holding the text. */
    static View target(View root) {
        if (root.getBackground() != null) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getBackground() != null && c instanceof ViewGroup && holdsText((ViewGroup) c)) return c;
        }
        return null;
    }
    private static boolean holdsText(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) return true;
            if (c instanceof ViewGroup && holdsText((ViewGroup) c)) return true;
        }
        return false;
    }
}

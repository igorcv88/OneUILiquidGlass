package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Method;

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

    private final LiveGlass glass;
    private Method getView;

    ToastHooks(LiveGlass glass) { this.glass = glass; }
    void setGetView(Method m) { getView = m; }

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

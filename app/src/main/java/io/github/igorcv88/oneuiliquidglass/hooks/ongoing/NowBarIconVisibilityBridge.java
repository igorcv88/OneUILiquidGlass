package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * A short-lived visual bridge for a measured Now Bar notification icon that Samsung keeps at
 * alpha=0 during the expand animation. Never changes the OEM view's alpha or drawable.
 * One drawable is added to its header overlay, then removed on the first visible native frame.
 */
final class NowBarIconVisibilityBridge
        implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private static final long MAX_BRIDGE_MS = 1800L;
    private WeakReference<ImageView> image = new WeakReference<>(null);
    private WeakReference<ImageView> expired = new WeakReference<>(null);
    private WeakReference<ViewGroup> parent = new WeakReference<>(null);
    private WeakReference<View> expandedRoot = new WeakReference<>(null);
    private ViewTreeObserver observer;
    private Drawable glyph;
    private long started;

    static boolean eligible(ImageView target, View root, boolean glass, String packageName) {
        ViewParent p = target == null ? null : target.getParent();
        return glass && packageName == null && target != null && root != null
                && "com.android.internal.widget.NotificationRowIconView".equals(target.getClass().getName())
                && p instanceof ViewGroup && target.isAttachedToWindow() && root.isShown()
                && target.getWidth() > 0 && target.getHeight() > 0
                && root.getHeight() > 2 * target.getHeight();
    }

    boolean active() { return glyph != null; }

    void sync(ImageView target, View root, boolean glass, String packageName) {
        if (!eligible(target, root, glass, packageName) || target.getAlpha() >= .98f) {
            restore();
            if (!glass) expired.clear();
            return;
        }
        // A view that never became visible must not start another bridge every 1.8 seconds.
        if (expired.get() == target) return;
        if (glyph != null && image.get() == target) return;
        restore();
        Drawable nativeGlyph = target.getDrawable();
        if (nativeGlyph == null || nativeGlyph.getConstantState() == null) return;
        Drawable copy;
        try {
            copy = nativeGlyph.getConstantState().newDrawable(target.getResources()).mutate();
            copy.setColorFilter(nativeGlyph.getColorFilter());
        } catch (RuntimeException | LinkageError ignored) { return; }
        ViewGroup owner = (ViewGroup) target.getParent();
        copy.setBounds(target.getLeft(), target.getTop(), target.getRight(), target.getBottom());
        owner.getOverlay().add(copy);
        glyph = copy;
        image = new WeakReference<>(target);
        parent = new WeakReference<>(owner);
        expandedRoot = new WeakReference<>(root);
        observer = owner.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnPreDrawListener(this);
        target.addOnAttachStateChangeListener(this);
        started = SystemClock.uptimeMillis();
        if (Probe.trace) Probe.log("NOWBAR_ICON_BRIDGE", "state=shown size="
                + target.getWidth() + "x" + target.getHeight() + " nativeAlpha=" + target.getAlpha());
    }

    @Override public boolean onPreDraw() {
        ImageView target = image.get();
        ViewGroup owner = parent.get();
        View root = expandedRoot.get();
        if (target == null || owner == null || root == null
                || !target.isAttachedToWindow() || !root.isShown()
                || target.getParent() != owner || target.getAlpha() >= .98f) {
            restore();
            return true;
        }
        if (SystemClock.uptimeMillis() - started >= MAX_BRIDGE_MS) {
            expired = new WeakReference<>(target);
            restore();
            return true;
        }
        // Samsung may move the header during the expansion. Follow geometry without touching
        // its drawable, tint, alpha or layout parameters.
        if (glyph != null && (glyph.getBounds().left != target.getLeft()
                || glyph.getBounds().top != target.getTop()
                || glyph.getBounds().right != target.getRight()
                || glyph.getBounds().bottom != target.getBottom()))
            glyph.setBounds(target.getLeft(), target.getTop(), target.getRight(), target.getBottom());
        return true;
    }

    @Override public void onViewAttachedToWindow(View v) { }
    @Override public void onViewDetachedFromWindow(View v) { restore(); }

    void restore() {
        if (glyph == null && observer == null) return;
        ImageView target = image.get();
        if (target != null) target.removeOnAttachStateChangeListener(this);
        ViewGroup owner = parent.get();
        if (owner != null && glyph != null) owner.getOverlay().remove(glyph);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        glyph = null;
        observer = null;
        image.clear();
        parent.clear();
        expandedRoot.clear();
        if (Probe.trace) Probe.log("NOWBAR_ICON_BRIDGE", "state=removed");
    }
}

package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/**
 * Temporary, read-only comparison of the approved notification glass and the live surfaces.
 * Each owner keeps its own instance; drawing or rebinding the same mode emits no more lines.
 * No notification text, package names, keys, RemoteViews or bitmap contents are collected.
 */
public final class LiveRenderProbe {
    private String last;
    private String lastReason;

    /** Returns true only when the observed path has changed (also when it returns to an old one). */
    public boolean changed(String path) {
        if (path.equals(last)) return false;
        last = path;
        lastReason = null;
        return true;
    }

    /** Two constant labels avoid concatenating strings on every animated pre-draw. */
    public boolean changed(String path, String reason) {
        if (path.equals(last) && reason.equals(lastReason)) return false;
        last = path;
        lastReason = reason;
        return true;
    }

    public static String view(View v) {
        if (v == null) return "view=null";
        try {
            View root = v.getRootView();
            Object params = root == null ? null : root.getLayoutParams();
            // A ViewRootImpl's window type is not always in the root view's LayoutParams.
            // Resolve it only on a mode transition, never in a stable pre-draw/draw path.
            if (!(params instanceof WindowManager.LayoutParams)) {
                try { params = Reflect.read(Reflect.call(v, "getViewRootImpl"), "mWindowAttributes"); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
            String type = params instanceof WindowManager.LayoutParams
                    ? String.valueOf(((WindowManager.LayoutParams) params).type) : "?";
            return "host=" + v.getClass().getSimpleName()
                    + " id=" + Integer.toHexString(System.identityHashCode(v))
                    + " attached=" + v.isAttachedToWindow() + " hwView=" + v.isHardwareAccelerated()
                    + " layerType=" + v.getLayerType() + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " alpha=" + v.getAlpha() + " windowType=" + type;
        } catch (RuntimeException | LinkageError e) {
            return "host=" + v.getClass().getSimpleName() + " readError=" + e.getClass().getSimpleName();
        }
    }

    /** A bounded, structural look at possible opaque layers between a live fill and its window. */
    public static String ancestors(View host) {
        if (host == null) return "-";
        StringBuilder b = new StringBuilder();
        Object current = host;
        try { for (int depth = 0; depth < 12 && current instanceof View; depth++) {
            View v = (View) current;
            Drawable d = v.getBackground();
            if (d != null) {
                if (b.length() > 0) b.append(';');
                b.append(depth).append(':').append(v.getClass().getSimpleName())
                        .append('/').append(d.getClass().getSimpleName())
                        .append(" alpha=").append(d.getAlpha());
                if (d instanceof ColorDrawable)
                    b.append(" argb=").append(Integer.toHexString(((ColorDrawable) d).getColor()));
                if (d.getClass().getName().contains("BlurDrawable")) {
                    Object radius = Reflect.read(d, "mBlurRadius");
                    Object position = Reflect.read(d, "mRect");
                    b.append(" blurRadius=").append(radius instanceof Number ? radius : "?")
                            .append(" positioned=").append(position instanceof android.graphics.Rect
                                    && !((android.graphics.Rect) position).isEmpty());
                }
            }
            ViewParent parent = v.getParent();
            current = parent instanceof View ? parent : null;
        }} catch (RuntimeException | LinkageError e) { b.append(";readError=").append(e.getClass().getSimpleName()); }
        return b.length() == 0 ? "none" : b.toString();
    }
}

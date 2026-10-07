package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Samsung realtime window blur (View.semSetBlurInfo). One UI renders it under the view's content,
 * so the material only adds edge optics. All members are resolved reflectively; a missing required
 * member keeps native rendering.
 */
public final class SemBlurBridge implements Backdrop {
    static final String INFO = "android.view.SemBlurInfo";
    private final View host;
    private final Method set;
    private final Constructor<?> builder;
    private final int mode;
    private final Method radius, color, corner, build;
    private Object pending;
    private boolean posted, released;
    private final Runnable apply = this::applyPending;

    private SemBlurBridge(View host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> info = Class.forName(INFO);
        Class<?> builderClass = Class.forName(INFO + "$Builder");
        set = View.class.getMethod("semSetBlurInfo", info);
        builder = builderClass.getConstructor(int.class);
        mode = info.getField("BLUR_MODE_WINDOW").getInt(null);
        radius = builderClass.getMethod("setRadius", int.class);
        build = builderClass.getMethod("build");
        color = optional(builderClass, "setBackgroundColor", int.class);
        corner = optional(builderClass, "setBackgroundCornerRadius", float.class);
    }
    private static Method optional(Class<?> c, String name, Class<?> arg) {
        try { return c.getMethod(name, arg); } catch (NoSuchMethodException e) { return null; }
    }
    public static boolean available() {
        try {
            Class<?> info = Class.forName(INFO);
            View.class.getMethod("semSetBlurInfo", info);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { return false; }
    }
    public static SemBlurBridge create(View host) throws ReflectiveOperationException {
        SemBlurBridge bridge = new SemBlurBridge(host);
        Probe.log("SEM_BLUR_BRIDGE", "mode=" + bridge.mode + " color=" + (bridge.color != null) + " corner=" + (bridge.corner != null));
        return bridge;
    }
    @Override public String name() { return "samsung"; }
    @Override public void update(int px, int tint, float[] radii) throws ReflectiveOperationException {
        Object b = builder.newInstance(mode);
        radius.invoke(b, px);
        if (color != null) color.invoke(b, tint);
        // Single scalar radius: callers only select this backdrop for uniform circular corners.
        if (corner != null) corner.invoke(b, radii[0]);
        pending = build.invoke(b);
        // Updates arrive from the hooked onDraw; apply after the current traversal, not mid-draw.
        if (!posted) { posted = true; host.post(apply); }
    }
    private void applyPending() {
        posted = false;
        if (released || pending == null) return;
        try { set.invoke(host, pending); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_APPLY_FAILED", e); }
    }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() {
        released = true; pending = null;
        host.removeCallbacks(apply);
        try { set.invoke(host, (Object) null); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_CLEAR_FAILED", e); }
    }
}

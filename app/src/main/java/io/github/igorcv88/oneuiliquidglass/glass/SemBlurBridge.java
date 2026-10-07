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
    // Builder(int mode) or Builder() + setBlurMode(int); the firmware's variant is logged.
    private final Constructor<?> builderWithMode, builderPlain;
    private final Method setMode;
    private final int mode;
    private final Method radius, color, corner, corners, build;
    private Object pending;
    private boolean posted, released;
    private final Runnable apply = this::applyPending;

    private SemBlurBridge(View host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> info = Class.forName(INFO);
        Class<?> builderClass = Class.forName(INFO + "$Builder");
        set = View.class.getMethod("semSetBlurInfo", info);
        // Recon on One UI 9 confirmed BLUR_MODE_WINDOW exists; it must be an int constant for this bridge.
        mode = info.getField("BLUR_MODE_WINDOW").getInt(null);
        builderWithMode = optionalCtor(builderClass, int.class);
        builderPlain = builderWithMode == null ? builderClass.getConstructor() : null;
        setMode = builderWithMode == null ? builderClass.getMethod("setBlurMode", int.class) : null;
        Method r = optional(builderClass, "setRadius", int.class);
        radius = r != null ? r : builderClass.getMethod("setBlurRadius", int.class);
        build = builderClass.getMethod("build");
        color = optional(builderClass, "setBackgroundColor", int.class);
        corner = optional(builderClass, "setBackgroundCornerRadius", float.class);
        Method four;
        try { four = builderClass.getMethod("setBackgroundCornerRadius", float.class, float.class, float.class, float.class); }
        catch (NoSuchMethodException e) { four = null; }
        corners = four;
    }
    private static Constructor<?> optionalCtor(Class<?> c, Class<?> arg) {
        try { return c.getConstructor(arg); } catch (NoSuchMethodException e) { return null; }
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
    /** Shapes the Samsung blur can reproduce without guessing a corner order. */
    public static boolean supports(Object shape) {
        return fourRadii() ? CornerGeometry.symmetric(shape) : CornerGeometry.uniform(shape);
    }
    private static Boolean fourRadii;
    private static boolean fourRadii() {
        if (fourRadii == null) {
            try {
                Class.forName(INFO + "$Builder").getMethod("setBackgroundCornerRadius", float.class, float.class, float.class, float.class);
                fourRadii = true;
            } catch (ReflectiveOperationException | LinkageError e) { fourRadii = false; }
        }
        return fourRadii;
    }
    public static SemBlurBridge create(View host) throws ReflectiveOperationException {
        SemBlurBridge bridge = new SemBlurBridge(host);
        Probe.log("SEM_BLUR_BRIDGE", "mode=" + bridge.mode + " builder=" + (bridge.builderWithMode != null ? "Builder(int)" : "Builder()+setBlurMode")
                + " radius=" + bridge.radius.getName() + " color=" + (bridge.color != null) + " corner=" + (bridge.corner != null) + " corners4=" + (bridge.corners != null));
        return bridge;
    }
    @Override public String name() { return "samsung"; }
    @Override public void update(int px, int tint, float[] radii) throws ReflectiveOperationException {
        Object b;
        if (builderWithMode != null) b = builderWithMode.newInstance(mode);
        else { b = builderPlain.newInstance(); setMode.invoke(b, mode); }
        radius.invoke(b, px);
        if (color != null) color.invoke(b, tint);
        // Four-radius setter assumed top-first (TL, TR, BL, BR as in AOSP); callers pass only shapes
        // with equal top and bottom pairs, so the bottom-pair order cannot matter.
        if (corners != null) corners.invoke(b, radii[0], radii[2], radii[6], radii[4]);
        else if (corner != null) corner.invoke(b, radii[0]);
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

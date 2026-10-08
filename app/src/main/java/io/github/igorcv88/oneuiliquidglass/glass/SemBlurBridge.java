package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Samsung realtime window blur (View.semSetBlurInfo). One UI renders it in the compositor under the
 * view, in the same frame as the app behind, so motion behind stays fluid; the material adds edge
 * optics, and an optional color curve (debug.oulg.semcurve) restores saturation. All members are resolved reflectively; a missing required
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
    // Color curve (saturation/contrast of the blurred backdrop): preset or explicit, both optional.
    private final Method curvePreset, curve;
    private final Method clipPath;
    private final Class<?> infoClass;
    private Object pending;
    private boolean posted, released;
    private final Runnable apply = this::applyPending;
    /** Last info this bridge applied; {@link #reassert()} applies it again. */
    private Object lastInfo;
    private int swallowed;
    // Inputs of the last build: a clip path is sized to the view and is rebuilt when the view resizes.
    private int lastPx, lastTint;
    private final float[] lastRadii = new float[8];
    /** Event-driven: a layout that resizes the view rebuilds a clip-path blur, nothing polls. */
    private final View.OnLayoutChangeListener resize = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (released || !"path".equals(this.lastShape) || (r - l == or - ol && b - t == ob - ot)) return;
        try { update(lastPx, lastTint, lastRadii); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_RESIZE_FAILED", e); }
    };

    private SemBlurBridge(View host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> info = Class.forName(INFO);
        infoClass = info;
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
        curvePreset = optional(builderClass, "setColorCurvePreset", int.class);
        clipPath = optional(builderClass, "setBackgroundClipPath", android.graphics.Path.class);
        Method six;
        try { six = builderClass.getMethod("setColorCurve", float.class, float.class, float.class, float.class, float.class, float.class); }
        catch (NoSuchMethodException e) { six = null; }
        curve = six;
    }
    private static Constructor<?> optionalCtor(Class<?> c, Class<?> arg) {
        try { return c.getConstructor(arg); } catch (NoSuchMethodException e) { return null; }
    }
    private static Method optional(Class<?> c, String name, Class<?> arg) {
        try { return c.getMethod(name, arg); } catch (NoSuchMethodException e) { return null; }
    }
    /**
     * The blur SystemUI itself last set on each notification view (it does this once, e.g. under
     * lockscreen notifications), recorded by the blur guard. Releasing a bridge restores it: clearing
     * to null erased that native blur for the row's lifetime.
     */
    private static final java.util.Map<View, Object> NATIVE = new java.util.WeakHashMap<>();
    /** Main thread only; called by the blur guard for every semSetBlurInfo call not made by a bridge. */
    public static void recordNative(View view, Object info) { NATIVE.put(view, info); }
    /**
     * Re-applies the recorded SystemUI blur on a view no bridge hosts (a notification row whose
     * blur calls were blocked while its background's material was managed). No-op if none recorded.
     */
    public static void restoreNative(View view) {
        if (!NATIVE.containsKey(view)) return;
        try {
            Class<?> info = Class.forName(INFO);
            applying = true;
            View.class.getMethod("semSetBlurInfo", info).invoke(view, NATIVE.get(view));
            Probe.log("SEM_BLUR_NATIVE_RESTORED", "viewId=" + Integer.toHexString(System.identityHashCode(view)) + " row=true");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { Probe.error("SEM_BLUR_NATIVE_RESTORE_FAILED", e); }
        finally { applying = false; }
    }
    /** Main thread only: true while this bridge itself calls semSetBlurInfo (see the blur guard). */
    private static boolean applying;
    public static boolean applying() { return applying; }
    /**
     * The view a bridge is applying its blur to, only for the duration of that call. Samsung's
     * semSetBlurInfo installs a BackgroundBlurDrawable through setBackground(), and SystemUI's
     * NotificationBackgroundView answers that with a nested semSetBlurInfo(null) that erased the
     * blur we had just set (device log: every apply followed in the same ms by a null on that view).
     */
    private static View applyingHost;
    public static View applyingHost() { return applyingHost; }
    public static boolean available() {
        try {
            Class<?> info = Class.forName(INFO);
            View.class.getMethod("semSetBlurInfo", info);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) { return false; }
    }
    /** Shapes the Samsung blur can reproduce without guessing a corner order. */
    public static boolean supports(Object shape) {
        // A clip path reproduces any circular-corner shape; the native fallback for other shapes
        // drew Samsung's dark background mid-animation and could stay recorded.
        if (clipPathAvailable()) return CornerGeometry.supported(shape);
        return fourRadii() ? CornerGeometry.symmetric(shape) : CornerGeometry.uniform(shape);
    }
    private static Boolean clipPathAvailable;
    private static boolean clipPathAvailable() {
        if (clipPathAvailable == null) {
            try {
                Class.forName(INFO + "$Builder").getMethod("setBackgroundClipPath", android.graphics.Path.class);
                clipPathAvailable = true;
            } catch (ReflectiveOperationException | LinkageError e) { clipPathAvailable = false; }
        }
        return clipPathAvailable;
    }
    private static java.lang.reflect.Field infoField;
    private static boolean infoFieldResolved;
    /** The View field holding the applied SemBlurInfo, found by type; null if the firmware has none. */
    private static java.lang.reflect.Field infoField(Class<?> info) {
        if (infoFieldResolved) return infoField;
        infoFieldResolved = true;
        try {
            for (java.lang.reflect.Field f : View.class.getDeclaredFields()) {
                if (f.getType() == info && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true); infoField = f; break;
                }
            }
        } catch (RuntimeException | LinkageError e) { infoField = null; }
        Probe.log("SEM_BLUR_FIELD", "field=" + (infoField == null ? "none" : infoField.getName()));
        return infoField;
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
        host.addOnLayoutChangeListener(bridge.resize);
        Probe.log("SEM_BLUR_BRIDGE", "mode=" + bridge.mode + " builder=" + (bridge.builderWithMode != null ? "Builder(int)" : "Builder()+setBlurMode")
                + " radius=" + bridge.radius.getName() + " color=" + (bridge.color != null) + " corner=" + (bridge.corner != null) + " corners4=" + (bridge.corners != null)
                + " curvePreset=" + (bridge.curvePreset != null) + " curve=" + (bridge.curve != null));
        return bridge;
    }
    @Override public String name() { return "samsung"; }
    @Override public void update(int px, int tint, float[] radii) throws ReflectiveOperationException {
        Object b;
        if (builderWithMode != null) b = builderWithMode.newInstance(mode);
        else { b = builderPlain.newInstance(); setMode.invoke(b, mode); }
        Tuning t = Tuning.get();
        radius.invoke(b, t.semRadius >= 0 ? t.semRadius : GlassSpec.SAMSUNG_RADIUS);
        String shape = applyShape(b, t.semShape, radii);
        lastPx = px; lastTint = tint; System.arraycopy(radii, 0, lastRadii, 0, 8);
        if (!shape.equals(lastShape)) { lastShape = shape; Probe.log("SEM_BLUR_SHAPE", "mode=" + shape + " radius=" + radii[0] + " size=" + host.getWidth() + "x" + host.getHeight()); }
        if (color != null) color.invoke(b, t.semAlpha >= 0 ? (tint & 0x00ffffff) | (t.semAlpha << 24) : tint);
        applyCurve(b, t.semCurve);
        pending = build.invoke(b);
        // Updates arrive from the hooked onDraw; apply after the current traversal, not mid-draw.
        if (!posted) { posted = true; host.post(apply); }
    }
    private String lastShape;
    /**
     * How the blur region is shaped. On device the four-radius setter left the blur only in a band
     * in the middle of the card, so a uniform shape now uses the single-radius setter; "path" clips
     * to the exact rounded rect, "four" is the old behaviour, "none" leaves the region rectangular
     * (debug.oulg.semshape).
     */
    private String applyShape(Object b, String mode, float[] radii) throws ReflectiveOperationException {
        boolean uniform = radii[0] == radii[2] && radii[2] == radii[4] && radii[4] == radii[6];
        if (mode.equals("auto")) mode = uniform && corner != null ? "single" : clipPath != null ? "path" : "four";
        switch (mode) {
            case "none": return mode;
            case "single":
                if (corner == null) break;
                corner.invoke(b, radii[0]);
                return mode;
            case "path":
                if (clipPath == null || host.getWidth() <= 0 || host.getHeight() <= 0) break;
                android.graphics.Path path = new android.graphics.Path();
                path.addRoundRect(new android.graphics.RectF(0, 0, host.getWidth(), host.getHeight()), radii, android.graphics.Path.Direction.CW);
                clipPath.invoke(b, path);
                return mode;
            default: break;
        }
        // Four-radius setter assumed top-first (TL, TR, BL, BR as in AOSP); callers pass only shapes
        // with equal top and bottom pairs, so the bottom-pair order cannot matter.
        if (corners != null) { corners.invoke(b, radii[0], radii[2], radii[6], radii[4]); return "four"; }
        if (corner != null) { corner.invoke(b, radii[0]); return "single"; }
        return "none";
    }
    /** spatial|dim|ultra pick Samsung's presets for the current theme; "s,c,x0,x1,y0,y1" is explicit. */
    private void applyCurve(Object builder, String spec) {
        if (spec == null || spec.isEmpty() || spec.equals("none")) return;
        try {
            if (spec.indexOf(',') >= 0) {
                if (curve == null) return;
                String[] p = spec.split(",");
                if (p.length != 6) return;
                Object[] v = new Object[6];
                for (int i = 0; i < 6; i++) v[i] = Float.parseFloat(p[i].trim());
                curve.invoke(builder, v);
                return;
            }
            if (curvePreset == null) return;
            boolean dark = (host.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            String field = "COLOR_CURVE_TYPE_" + spec.toUpperCase(java.util.Locale.ROOT) + "_BACKGROUND_" + (dark ? "DARK" : "LIGHT");
            curvePreset.invoke(builder, infoClass.getField(field).getInt(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Probe.log("SEM_BLUR_CURVE_FAILED", "spec=" + spec + " error=" + e.getClass().getSimpleName());
        }
    }
    private void applyPending() {
        posted = false;
        if (released || pending == null) return;
        try {
            applying = true; applyingHost = host;
            java.lang.reflect.Field f = infoField(infoClass);
            Object before = f != null ? f.get(host) : null;
            set.invoke(host, pending);
            lastInfo = pending;
            // The view holds our info or a fresh copy of it. If another hook swallowed the call, the
            // field keeps the old or foreign value; that is logged, and the next row event retries.
            Object after = f != null ? f.get(host) : pending;
            if (after != pending && (after == null || after == before) && (++swallowed <= 10 || swallowed % 100 == 0)) {
                Probe.log("SEM_BLUR_SWALLOWED", "viewId=" + Integer.toHexString(System.identityHashCode(host))
                        + " held=" + (after == null ? "null" : "foreign") + " count=" + swallowed);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_APPLY_FAILED", e); }
        finally { applying = false; applyingHost = null; }
    }
    /**
     * Applies the last blur again. Called on events that can drop it (shade opened or closed, row
     * state change, a framework blur method touching the view), never on a timer or per frame.
     */
    @Override public void reassert() {
        if (released || lastInfo == null || posted) return;
        pending = lastInfo; posted = true; host.post(apply);
    }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() {
        released = true; pending = null;
        host.removeCallbacks(apply);
        host.removeOnLayoutChangeListener(resize);
        Object restore = NATIVE.get(host);
        try { applying = true; set.invoke(host, restore); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_CLEAR_FAILED", e); }
        finally { applying = false; }
        if (restore != null) Probe.log("SEM_BLUR_NATIVE_RESTORED", "viewId=" + Integer.toHexString(System.identityHashCode(host)));
    }
}

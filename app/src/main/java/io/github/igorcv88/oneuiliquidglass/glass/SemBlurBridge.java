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
    // Last info this bridge applied, and what the view held right after: if the view later holds
    // anything else, the blur was replaced or dropped behind our back and is applied again.
    private Object lastInfo, applied;
    private long lastReassert;
    private int reasserts;
    // Inputs of the last build: a clip path is sized to the view and is rebuilt when the view resizes.
    private int lastPx, lastTint, builtWidth, builtHeight;
    private final float[] lastRadii = new float[8];

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
    /** Main thread only: true while this bridge itself calls semSetBlurInfo (see the blur guard). */
    private static boolean applying;
    public static boolean applying() { return applying; }
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
        builtWidth = host.getWidth(); builtHeight = host.getHeight();
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
            applying = true; set.invoke(host, pending);
            lastInfo = pending;
            java.lang.reflect.Field f = infoField(infoClass);
            applied = f != null ? f.get(host) : pending;
        }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_APPLY_FAILED", e); }
        finally { applying = false; }
    }
    /**
     * Called every frame: re-applies the blur when the view no longer holds what this bridge set
     * (another component replaced it, or the framework dropped it). Without this the card kept
     * Samsung's dark look until the next notification rebuilt every material.
     */
    @Override public boolean verify() {
        if (released || posted || lastInfo == null) return false;
        if ("path".equals(lastShape) && (host.getWidth() != builtWidth || host.getHeight() != builtHeight)) {
            try { update(lastPx, lastTint, lastRadii); return true; }
            catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_RESIZE_FAILED", e); return false; }
        }
        java.lang.reflect.Field f = infoField;
        if (f == null) return false;
        Object current;
        try { current = f.get(host); } catch (IllegalAccessException | RuntimeException e) { return false; }
        // Our own info held directly is healthy too, e.g. if the field is written after the setter returns.
        if (current == applied || current == lastInfo) { applied = current; return false; }
        long now = android.os.SystemClock.uptimeMillis();
        // Bounded: something replacing the blur every frame costs at most five re-applies a second.
        if (now - lastReassert < 200) return false;
        lastReassert = now;
        if (++reasserts <= 20 || reasserts % 100 == 0) {
            Probe.log("SEM_BLUR_REASSERT", "viewId=" + Integer.toHexString(System.identityHashCode(host))
                    + " held=" + (current == null ? "null" : "foreign") + " count=" + reasserts);
        }
        reassert();
        return true;
    }
    /** Applies the last blur again, e.g. after the shade opened or the row changed state. */
    @Override public void reassert() {
        if (released || lastInfo == null || posted) return;
        pending = lastInfo; posted = true; host.post(apply);
    }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() {
        released = true; pending = null;
        host.removeCallbacks(apply);
        try { applying = true; set.invoke(host, (Object) null); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_CLEAR_FAILED", e); }
        finally { applying = false; }
    }
}

package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import android.view.ViewParent;
import java.util.Arrays;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

public final class Probe {
    private Probe() {}
    /**
     * Diagnostic dumps that walk view trees or windows (scrims, painters, hierarchy, view and
     * material snapshots, window surveys) run on SystemUI's main thread, some of them as the shade
     * starts to open; off unless debug.oulg.trace=1. Single-line events are always logged.
     */
    public static volatile boolean trace;
    public static void log(String event, String detail) {
        Log.i("OULG", "v=1 t=" + SystemClock.elapsedRealtime() + " event=" + event + " " + detail);
    }
    public static void error(String event, Throwable error) {
        error(event, null, error);
    }
    /**
     * The exception's class and message (a NoSuchMethodException names the member that is
     * missing), plus what was being done; never a stack trace or user data.
     */
    public static void error(String event, String step, Throwable error) {
        String message = error.getMessage();
        if (message != null && message.length() > 600) message = message.substring(0, 600) + "…";
        log(event, (step == null ? "" : "step=" + step + " ") + "error=" + error.getClass().getSimpleName()
                + (message == null ? "" : " message=" + message.replace('\n', ' ')));
    }
    private static String prop(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class).invoke(null, key);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return "unknown"; }
    }
    public static void firmware() {
        log("PROCESS", "model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " oneui=" + prop("ro.build.version.oneui") + " fingerprint=" + Build.FINGERPRINT);
    }
    /** Inputs AOSP combines into WindowManager.isCrossWindowBlurEnabled(). */
    public static void blurEnvironment(Context context) {
        String disabled = null; Boolean powerSave = null;
        try { disabled = Settings.Global.getString(context.getContentResolver(), "disable_window_blurs"); }
        catch (RuntimeException ignored) { }
        try { PowerManager pm = context.getSystemService(PowerManager.class); if (pm != null) powerSave = pm.isPowerSaveMode(); }
        catch (RuntimeException ignored) { }
        log("BLUR_ENV", "supportsBackgroundBlur=" + prop("ro.surface_flinger.supports_background_blur")
                + " disableWindowBlurs=" + disabled + " powerSave=" + powerSave);
        // Samsung's own realtime blur, used when AOSP cross-window blur is unsupported.
        boolean semBlurInfo = false, semSetBlurInfo = false;
        try { Class.forName("android.view.SemBlurInfo"); semBlurInfo = true; } catch (ClassNotFoundException | LinkageError ignored) { }
        try {
            for (Method m : View.class.getMethods()) if (m.getName().equals("semSetBlurInfo")) { semSetBlurInfo = true; break; }
        } catch (LinkageError | RuntimeException ignored) { }
        log("SEM_BLUR", "SemBlurInfo=" + semBlurInfo + " View.semSetBlurInfo=" + semSetBlurInfo);
        // Names for the Samsung backdrop; logged so a firmware mismatch is visible without guessing.
        for (String name : new String[]{"android.view.SemBlurInfo", "android.view.SemBlurInfo$Builder"}) {
            try {
                Class<?> c = Class.forName(name);
                for (Field f : c.getFields()) if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    log("SEM_BLUR_FIELD", "owner=" + name + " name=" + f.getName());
                for (Method m : c.getDeclaredMethods())
                    log("SEM_BLUR_METHOD", "owner=" + name + " name=" + m.getName() + " args=" + Arrays.toString(m.getParameterTypes()));
                for (java.lang.reflect.Constructor<?> k : c.getConstructors())
                    log("SEM_BLUR_CTOR", "owner=" + name + " args=" + Arrays.toString(k.getParameterTypes()));
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) { }
        }
    }
    public static void resolved(Class<?> c) {
        log("CLASS", "name=" + c.getName());
        for (Field f : c.getDeclaredFields()) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("background") || n.contains("corner") || n.contains("expand") || n.contains("headsup") || n.contains("keyguard") || n.contains("surface") || n.contains("pin") || n.contains("blur"))
                log("FIELD", "owner=" + c.getName() + " name=" + f.getName() + " type=" + f.getType().getName());
        }
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("headsup") || n.contains("appear") || n.contains("background") || n.contains("radius") || n.contains("expand") || n.contains("show") || n.contains("dismiss") || n.contains("pin") || n.contains("blur"))
                log("METHOD", "owner=" + c.getName() + " name=" + m.getName() + " args=" + Arrays.toString(m.getParameterTypes()));
        }
    }
    public static void hierarchy(View view) {
        if (!trace) return;
        View current = view;
        for (int depth = 0; current != null && depth < 16; depth++) {
            log("ANCESTOR", "depth=" + depth + " class=" + current.getClass().getName());
            ViewParent parent = current.getParent(); current = parent instanceof View ? (View) parent : null;
        }
    }
    public static void view(String event, View view) {
        if (!trace) return;
        int[] xy = new int[2]; view.getLocationOnScreen(xy);
        WindowInsets insets = view.getRootWindowInsets();
        Object root = null;
        try { root = Reflect.call(view, "getViewRootImpl"); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        Object attrs = Reflect.read(root, "mWindowAttributes");
        log(event, "view=" + view.getClass().getName() + " id=" + Integer.toHexString(System.identityHashCode(view))
                + " attached=" + view.isAttachedToWindow() + " hardware=" + view.isHardwareAccelerated()
                + " xy=" + xy[0] + "," + xy[1] + " size=" + view.getWidth() + "x" + view.getHeight()
                + " root=" + (root == null ? "null" : root.getClass().getName())
                + " rootId=" + Integer.toHexString(System.identityHashCode(root))
                + " windowType=" + Reflect.read(attrs, "type") + " flags=" + Reflect.read(attrs, "flags")
                + " alpha=" + view.getAlpha() + " ime=" + (insets != null && insets.isVisible(WindowInsets.Type.ime())));
    }
    /**
     * Every view under root that carries a compositor blur: a SemBlurInfo on the view, a background
     * or a Drawable field whose class names a blur. One BLUR_CENSUS line each, with position, size,
     * alpha and the corner fields, so a blur no hook knows about can be found by where it sits.
     */
    public static void blurCensus(View root, String reason) {
        if (!trace || root == null) return;
        int[] count = {0};
        log("BLUR_CENSUS_BEGIN", "reason=" + reason);
        census(root, count);
        log("BLUR_CENSUS_END", "views=" + count[0]);
    }
    /**
     * The lockscreen's overflow pill, found by name: every shown view whose class names a shelf, an
     * icon container, an overflow or a footer, and its children, with position, size, alpha, the
     * background and the Drawable fields that may paint the grey pill. One OVERFLOW line each.
     */
    public static void overflow(View root) {
        if (!trace || root == null) return;
        int[] budget = {60};
        log("OVERFLOW_BEGIN", "root=" + root.getClass().getName());
        walkOverflow(root, false, 0, budget);
        log("OVERFLOW_END", "left=" + budget[0]);
    }
    private static void walkOverflow(View v, boolean inside, int depth, int[] budget) {
        if (budget[0] <= 0 || depth > 24) return;
        String simple = v.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        boolean match = simple.contains("shelf") || simple.contains("iconcontainer") || simple.contains("overflow") || simple.contains("footer") || simple.contains("pill");
        if ((match || inside) && v.isShown()) {
            budget[0]--;
            int[] xy = new int[2]; v.getLocationOnScreen(xy);
            String id;
            try { id = v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId()); } catch (RuntimeException e) { id = "?"; }
            StringBuilder fieldsOut = new StringBuilder();
            for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    boolean drawable = android.graphics.drawable.Drawable.class.isAssignableFrom(f.getType());
                    boolean view = View.class.isAssignableFrom(f.getType()) && f.getName().toLowerCase(java.util.Locale.ROOT).contains("background");
                    if (!drawable && !view) continue;
                    try {
                        f.setAccessible(true);
                        Object d = f.get(v);
                        if (d == null) continue;
                        fieldsOut.append(' ').append(f.getName()).append('=')
                                .append(drawable ? describe((android.graphics.drawable.Drawable) d, 0) : d.getClass().getName());
                    } catch (ReflectiveOperationException | RuntimeException e) { fieldsOut.append(' ').append(f.getName()).append("=?"); }
                }
            }
            StringBuilder supers = new StringBuilder();
            for (Class<?> c = v.getClass().getSuperclass(); c != null && c != View.class && supers.length() < 200; c = c.getSuperclass()) supers.append('<').append(c.getSimpleName());
            log("OVERFLOW", "view=" + v.getClass().getName() + supers + " id=" + id + " depth=" + depth + " xy=" + xy[0] + "," + xy[1]
                    + " size=" + v.getWidth() + "x" + v.getHeight() + " alpha=" + v.getAlpha() + " scale=" + v.getScaleX() + "x" + v.getScaleY()
                    + " bg=" + describe(v.getBackground(), 0) + fieldsOut);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkOverflow(g.getChildAt(i), inside || match, depth + 1, budget);
        }
    }
    private static java.lang.reflect.Field viewInfoField;
    private static boolean viewInfoResolved;
    private static void census(View v, int[] count) {
        if (count[0] >= 80) return;
        StringBuilder found = new StringBuilder();
        try {
            if (!viewInfoResolved) {
                viewInfoResolved = true;
                for (java.lang.reflect.Field f : View.class.getDeclaredFields()) {
                    if (f.getType().getName().equals("android.view.SemBlurInfo") && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); viewInfoField = f; }
                }
            }
            Object info = viewInfoField != null ? viewInfoField.get(v) : null;
            if (info != null) found.append(" semInfo={").append(fields(info)).append('}');
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg != null && bg.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("blur")) found.append(" background=").append(bg.getClass().getName())
                    .append(" bounds=").append(bg.getBounds().toShortString()).append('{').append(fields(bg)).append('}');
            for (Class<?> c = v.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !android.graphics.drawable.Drawable.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object d = f.get(v);
                    if (d != null && d != bg && d.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("blur")) {
                        found.append(' ').append(f.getName()).append('=').append(d.getClass().getName()).append('{').append(fields(d)).append('}');
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { found.append(" error=").append(e.getClass().getSimpleName()); }
        if (found.length() > 0) {
            count[0]++;
            int[] xy = new int[2]; v.getLocationOnScreen(xy);
            String id;
            try { id = v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId()); } catch (RuntimeException e) { id = "?"; }
            log("BLUR_CENSUS", "view=" + v.getClass().getName() + " id=" + id + " obj=" + Integer.toHexString(System.identityHashCode(v))
                    + " shown=" + v.isShown() + " xy=" + xy[0] + "," + xy[1] + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " alpha=" + v.getAlpha() + transforms(v) + found);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) census(g.getChildAt(i), count);
        }
    }
    /**
     * Scale, translation and clip state of the view and of its notification row, if any: a stacked
     * or clipped card can draw its blur region at a size that no longer carries the corner tag.
     */
    private static String transforms(View v) {
        StringBuilder out = new StringBuilder(" scale=").append(v.getScaleX()).append('x').append(v.getScaleY())
                .append(" ty=").append(v.getTranslationY());
        View row = null;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().endsWith("ExpandableNotificationRow")) { row = (View) p; break; }
        }
        if (row != null) {
            out.append(" row{scale=").append(row.getScaleX()).append('x').append(row.getScaleY()).append(" ty=").append(row.getTranslationY())
                    .append(" alpha=").append(row.getAlpha());
            for (String f : new String[]{"mActualHeight", "mClipTopAmount", "mClipBottomAmount", "mClipToActualHeight", "mInShelf", "mTransformingInShelf"}) {
                Object val = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(row, f);
                if (val != null) out.append(' ').append(f.substring(1)).append('=').append(val);
            }
            android.graphics.Rect clip = row.getClipBounds();
            if (clip != null) out.append(" clip=").append(clip.toShortString());
            out.append('}');
        }
        return out.toString();
    }
    /** Corner, radius, alpha and color fields of a blur object, by name. */
    /** Radius, colour, curve and shape fields of a blur info or drawable, for the trace. */
    public static String blurFields(Object o) {
        StringBuilder out = new StringBuilder(fields(o));
        // Nested Samsung blur parts (colour curve, colour adjustment, gradient): every field.
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().getName().startsWith("android.view.SemBlurInfo$")) continue;
                try {
                    f.setAccessible(true);
                    Object nested = f.get(o);
                    if (nested == null) continue;
                    out.append(' ').append(f.getName()).append("={");
                    for (java.lang.reflect.Field g : nested.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(g.getModifiers())) continue;
                        g.setAccessible(true);
                        Object val = g.get(nested);
                        if (val instanceof float[]) val = java.util.Arrays.toString((float[]) val);
                        out.append(g.getName()).append('=').append(val).append(' ');
                    }
                    out.append('}');
                } catch (ReflectiveOperationException | RuntimeException e) { out.append(' ').append(f.getName()).append("=?"); }
            }
        }
        return out.toString();
    }
    private static String fields(Object o) {
        StringBuilder out = new StringBuilder();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!(n.contains("corner") || n.contains("radius") || n.contains("alpha") || n.contains("color") || n.contains("path") || n.contains("mode"))) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(o);
                    if (val instanceof float[]) val = java.util.Arrays.toString((float[]) val);
                    else if (val instanceof Integer && n.contains("color")) val = Integer.toHexString((Integer) val);
                    else if (val != null && !(val instanceof Number || val instanceof Boolean)) val = val.getClass().getSimpleName();
                    out.append(f.getName()).append('=').append(val).append(' ');
                } catch (ReflectiveOperationException | RuntimeException e) { out.append(f.getName()).append("=? "); }
            }
        }
        return out.toString().trim();
    }
    /** What paints the native material: drawable chain, alphas and tint, logged when glass engages. */
    public static void material(View background, View row, android.graphics.drawable.Drawable original,
                                String backdrop, int fill, int blurColor) {
        if (!trace) return;
        log("MATERIAL", "viewId=" + Integer.toHexString(System.identityHashCode(background)) + " backdrop=" + backdrop
                + " viewAlpha=" + background.getAlpha() + " rowAlpha=" + (row == null ? "null" : row.getAlpha())
                + " transitionAlpha=" + Reflect.read(background, "mTransitionAlpha")
                + " tint=" + hex(Reflect.read(background, "mTintColor")) + " fill=" + hex(fill) + " blurColor=" + hex(blurColor)
                + " nativeBlur=" + Reflect.read(background, "mBlurEnabled") + " drawable=" + describe(original, 0));
    }
    /** Scrims, overlays and tinted backgrounds in the shade window: alpha, visibility and tint. */
    public static void scrims(View root, String reason) {
        if (root == null || !trace) return;
        int[] budget = {40};
        walkScrims(root, 0, reason, budget);
    }
    private static void walkScrims(View v, int depth, String reason, int[] budget) {
        if (budget[0] <= 0 || depth > 14) return;
        String name = v.getClass().getName();
        String simple = v.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        // "dim" matched every *ImageView ("animateDImageview", "precomputeDImage..."), which used up
        // the budget before the field dump of the real scrims.
        if (simple.contains("scrim") || simple.contains("blur")) {
            budget[0]--;
            Object viewAlpha = null, tint = null;
            try { viewAlpha = Reflect.call(v, "getViewAlpha"); } catch (ReflectiveOperationException | RuntimeException ignored) { }
            try { tint = Reflect.call(v, "getTint"); } catch (ReflectiveOperationException | RuntimeException ignored) { }
            String id = "";
            try { if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId()); } catch (RuntimeException ignored) { }
            log("SCRIM", "reason=" + reason + " class=" + name + " idName=" + id + " shown=" + v.isShown() + " alpha=" + v.getAlpha()
                    + " viewAlpha=" + viewAlpha + " tint=" + hex(tint) + " size=" + v.getWidth() + "x" + v.getHeight()
                    + " bg=" + describe(v.getBackground(), 0));
            scrimFields(v, reason);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkScrims(g.getChildAt(i), depth + 1, reason, budget);
        }
    }
    /**
     * Scalar and drawable fields declared by SystemUI scrim classes. The AOSP getters (getViewAlpha,
     * getTint) are absent on One UI 9, so the state is read from fields; each class is dumped once
     * per shade transition kind to keep the log bounded.
     */
    private static final java.util.Set<String> SCRIM_DUMPED = new java.util.HashSet<>();
    private static void scrimFields(View v, String reason) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        String key = v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v)) + "/" + reason;
        if (SCRIM_DUMPED.size() > 256 || !SCRIM_DUMPED.add(key)) return;
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Class<?> c = v.getClass(); c != null && !c.getName().startsWith("android."); c = c.getSuperclass()) {
            Field[] fields;
            try { fields = c.getDeclaredFields(); } catch (LinkageError e) { break; }
            for (Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || n >= 32) continue;
                Class<?> t = f.getType();
                boolean drawable = android.graphics.drawable.Drawable.class.isAssignableFrom(t);
                if (!t.isPrimitive() && t != Integer.class && t != Float.class && !drawable) continue;
                Object value;
                try { f.setAccessible(true); value = f.get(v); } catch (ReflectiveOperationException | RuntimeException e) { continue; }
                if (drawable) value = describe((android.graphics.drawable.Drawable) value, 1) + scrimDrawable(value);
                else if (t == int.class && f.getName().toLowerCase(java.util.Locale.ROOT).contains("color")) value = hex(value);
                b.append(' ').append(f.getName()).append('=').append(value);
                n++;
            }
        }
        log("SCRIM_FIELDS", "reason=" + reason + " id=" + Integer.toHexString(System.identityHashCode(v))
                + " class=" + v.getClass().getName() + " xy=" + xy[0] + "," + xy[1] + b);
    }
    /** The color and alpha a ScrimDrawable-like drawable actually paints with. */
    private static String scrimDrawable(Object d) {
        if (d == null) return "";
        Object main = Reflect.read(d, "mMainColor"), alpha = Reflect.read(d, "mAlpha");
        return main == null && alpha == null ? "" : "[main=" + hex(main) + " alpha=" + alpha + "]";
    }
    /**
     * Views under a notification row that paint their own background, with alpha and drawable, so
     * an opaque layer covering the glass (e.g. without a Theme Park theme) can be named. Once per row.
     */
    private static final java.util.Set<Integer> PAINTERS_DUMPED = new java.util.HashSet<>();
    public static void painters(View row) {
        if (row == null || !trace || PAINTERS_DUMPED.size() > 32 || !PAINTERS_DUMPED.add(System.identityHashCode(row))) return;
        int[] budget = {30};
        walkPainters(row, 0, Integer.toHexString(System.identityHashCode(row)), budget);
    }
    private static void walkPainters(View v, int depth, String rowId, int[] budget) {
        if (budget[0] <= 0 || depth > 12) return;
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg != null && bg.getAlpha() > 0 && v.getWidth() > 0 && v.getHeight() > 0) {
            budget[0]--;
            log("PAINTER", "row=" + rowId + " depth=" + depth + " class=" + v.getClass().getName() + " shown=" + v.isShown()
                    + " alpha=" + v.getAlpha() + " size=" + v.getWidth() + "x" + v.getHeight() + " bg=" + describe(bg, 0));
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkPainters(g.getChildAt(i), depth + 1, rowId, budget);
        }
    }
    private static String describe(android.graphics.drawable.Drawable d, int depth) {
        if (d == null) return "null";
        StringBuilder b = new StringBuilder(d.getClass().getSimpleName()).append("{alpha=").append(d.getAlpha());
        if (d instanceof android.graphics.drawable.ColorDrawable) b.append(" color=").append(hex(((android.graphics.drawable.ColorDrawable) d).getColor()));
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            android.content.res.ColorStateList c = ((android.graphics.drawable.GradientDrawable) d).getColor();
            b.append(" color=").append(c == null ? "null" : hex(c.getDefaultColor()));
        }
        if (d.getColorFilter() != null) b.append(" filter=").append(d.getColorFilter().getClass().getSimpleName());
        if (d instanceof android.graphics.drawable.LayerDrawable && depth < 2) {
            android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < l.getNumberOfLayers() && i < 6; i++) b.append(" L").append(i).append('=').append(describe(l.getDrawable(i), depth + 1));
        } else if (d instanceof android.graphics.drawable.DrawableWrapper && depth < 2) {
            b.append(" inner=").append(describe(((android.graphics.drawable.DrawableWrapper) d).getDrawable(), depth + 1));
        }
        return b.append('}').toString();
    }
    private static String hex(Object color) {
        return color instanceof Integer ? String.format("#%08x", (Integer) color) : String.valueOf(color);
    }
}

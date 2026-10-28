package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.Configuration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind;

/**
 * Liquid Glass for One UI 9's Quick Settings, which Samsung draws in Compose
 * (SecQSPanelCompose attaches a ComposeView to SecQSPanelComposeRoot; the legacy SecQSTileView
 * classes still ship but do not draw the panel, S938BXXUCDZIF).
 *
 * <p>Every visible pill, circle, tile group, slider and empty media card passes through Samsung's
 * {@code BoxWithOutline(modifier, outlined, width, outlineColor, backgroundColor, shape, content)}:
 * a border on the outer box, then a clipped inner box with a background. Only the values Samsung
 * hands to those two modifier elements change: the outline becomes a uniform basal light rim and
 * the background a translucent veil. Layout, modifier chains, Composer state, gestures,
 * semantics and drawing order are Samsung's. With the compositor (sfhook profile 3), each glass
 * container also gets a tagged region for the backdrop-dependent edge light
 * (QuickSettingsEdgeLight); without it, no compositor region exists.</p>
 *
 * <p>Only outer containers get the rim; circles inside them keep Samsung's outline. The slider
 * track and fill are drawn inset inside the glass pill (drawing only: layout, touch area and drag
 * are unchanged).</p>
 *
 * <p>Members are matched by name and descriptor; any one missing leaves the panel native.</p>
 */
public final class QuickSettingsHooks {
    private static final String BOX =
            "com.android.systemui.samsung.quicksetting.ui.common.uielement.BoxWithOutlineKt";
    private static final String BOX_METHOD = "BoxWithOutline-8QY48No";
    private static final String PANEL = "com.android.systemui.samsung.quicksetting.SecQSPanelCompose";
    private static final String BAR = "com.android.systemui.samsung.quicksetting.ui.tiles.BidirectionalBarKt";
    private static final String BAR_DRAW = "drawBidirectionalBarTrackProgressThumbContent-Uzs9YN0";
    /** $default mask bit of BoxWithOutline's {@code outlined}; its default is true. */
    private static final int OUTLINED_DEFAULT = 0x2;
    private static final int MAX_LOGGED = 48;

    private final ClassLoader loader;
    private final BooleanSupplier master;
    private final Consumer<android.content.Context> startConfig;
    /** BoxWithOutline's own recompose scopes, invalidated when the setting changes (live switch). */
    private final Set<Object> scopes = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<String> logged = new HashSet<>();
    private final QuickSettingsEdgeLight edge;
    private Object circleShape;
    private Method recomposeScope, recordUsed, invalidate, valid;
    private Method drawContext, contextSize, setContextSize, contextCanvas;
    private Field nativeCanvas;
    private Constructor<?> solidColor;
    private Object darkRim, lightRim;
    private boolean ready, broken, configStarted, applied;
    private android.content.Context app;
    /**
     * Set by BoxWithOutline for the two element constructors it runs next. Per thread: a box being
     * composed on one thread can never pick up, or clear, another thread's state.
     */
    private final ThreadLocal<Pending> pending = new ThreadLocal<>();

    private static final class Pending {
        final Object composer;
        final long background, fill;
        final Object rim;
        /** A glass container's shape and outline width (dp), for its edge light; null for circles. */
        final Object edgeShape;
        final float edgeBorder;
        boolean rimDone, fillDone;
        Pending(Object composer, long background, long fill, Object rim, Object edgeShape, float edgeBorder) {
            this.composer = composer; this.background = background; this.fill = fill; this.rim = rim;
            this.edgeShape = edgeShape; this.edgeBorder = edgeBorder;
            // Samsung builds no background element for a transparent colour: nothing to recolour.
            fillDone = background == 0L;
        }
    }

    public QuickSettingsHooks(ClassLoader loader, BooleanSupplier master,
            Consumer<android.content.Context> startConfig) {
        this.loader = loader;
        this.master = master;
        this.startConfig = startConfig;
        this.edge = new QuickSettingsEdgeLight(loader, this::enabled);
    }

    private boolean enabled() {
        return !broken && master.getAsBoolean() && ConfigClient.flag(ProductFlags.QUICK_SETTINGS);
    }

    public void install() {
        try {
            Class<?> box = resolve(BOX);
            Class<?> border = resolve("androidx.compose.foundation.BorderModifierNodeElement");
            Class<?> background = resolve("androidx.compose.foundation.BackgroundElement");
            Class<?> solid = resolve("androidx.compose.ui.graphics.SolidColor");
            Class<?> shapes = resolve("androidx.compose.foundation.shape.RoundedCornerShapeKt");
            Method boxMethod = box == null ? null : boxMethod(box);
            Constructor<?> borderInit = constructor(border, float.class, 3);
            Constructor<?> backgroundInit = constructor(background, long.class, 5);
            solidColor = solid == null ? null : solid.getDeclaredConstructor(long.class);
            Field circle = shapes == null ? null : shapes.getDeclaredField("CircleShape");
            if (boxMethod == null || borderInit == null || backgroundInit == null || solidColor == null
                    || circle == null) {
                Probe.log("QS_COMPOSE_MISSING", "box=" + (boxMethod != null) + " border=" + (borderInit != null)
                        + " background=" + (backgroundInit != null) + " solid=" + (solidColor != null)
                        + " circle=" + (circle != null));
                return;
            }
            circle.setAccessible(true);
            circleShape = circle.get(null);
            solidColor.setAccessible(true);
            resolveScopes();
            XposedBridge.hookMethod(boxMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { beforeBox(p.args); }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    pending.remove();
                }
            });
            XposedBridge.hookMethod(borderInit, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Pending q = pending.get();
                    if (q == null || q.rimDone) return;
                    q.rimDone = true; // The outer box's border is the first one BoxWithOutline builds.
                    capture(q.composer);
                    if (q.rim != null && p.args[0] instanceof Float && (Float) p.args[0] > 0f) p.args[1] = q.rim;
                }
            });
            XposedBridge.hookMethod(backgroundInit, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Pending q = pending.get();
                    if (q == null || q.fillDone) return;
                    if (!(p.args[0] instanceof Long) || (Long) p.args[0] != q.background) return;
                    q.fillDone = true;
                    p.args[0] = q.fill;
                    if (q.edgeShape != null) p.setObjectExtra("oulgEdge", q);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object q = p.getObjectExtra("oulgEdge");
                    if (q instanceof Pending) edge.element(p.thisObject, ((Pending) q).edgeShape, ((Pending) q).edgeBorder);
                }
            });
            edge.install(background);
            // ART may have inlined the small element constructors into these callers (AOT/JIT), which
            // would bypass the constructor hooks; run them interpreted instead.
            deoptimize(boxMethod, named(resolve("androidx.compose.foundation.BorderKt"), "border-xT4_qwU"),
                    named(resolve("androidx.compose.foundation.BackgroundKt"), "background-bw27NRU"));
            for (Constructor<?> k : border.getDeclaredConstructors()) if (k != borderInit) deoptimize(k);
            for (Constructor<?> k : background.getDeclaredConstructors()) if (k != backgroundInit) deoptimize(k);
            hookPanel();
            hookSlider();
            ready = true;
            Probe.log("QS_COMPOSE_READY", "method=" + boxMethod.toGenericString()
                    + " liveRefresh=" + (invalidate != null)
                    + " optIn=" + ConfigClient.flag(ProductFlags.QUICK_SETTINGS));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.error("QS_COMPOSE_INSTALL_FAILED", e);
        }
    }

    private Class<?> resolve(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            Probe.log("QS_CLASS_MISSING", "class=" + name);
            return null;
        }
    }

    /** BoxWithOutline(Modifier, boolean, float, long, long, RoundedCornerShape, Function2, Composer, int, int). */
    private static Method boxMethod(Class<?> box) {
        for (Method m : box.getDeclaredMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (m.getName().equals(BOX_METHOD) && t.length == 10 && t[1] == boolean.class
                    && t[2] == float.class && t[3] == long.class && t[4] == long.class
                    && t[5].getName().equals("androidx.compose.foundation.shape.RoundedCornerShape")
                    && t[7].getName().equals("androidx.compose.runtime.Composer")) return m;
        }
        return null;
    }

    private static Constructor<?> constructor(Class<?> c, Class<?> first, int count) {
        if (c == null) return null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] t = k.getParameterTypes();
            // The real constructor, not Kotlin's DefaultConstructorMarker bridges that call it.
            if (t.length == count && t[0] == first) return k;
        }
        return null;
    }

    /** RecomposeScope.invalidate() is stripped by R8; invalidateForResult(null) is the same call. */
    private void resolveScopes() {
        try {
            Class<?> composer = Class.forName("androidx.compose.runtime.Composer", false, loader);
            Class<?> scope = Class.forName("androidx.compose.runtime.RecomposeScope", false, loader);
            Class<?> impl = Class.forName("androidx.compose.runtime.RecomposeScopeImpl", false, loader);
            recomposeScope = composer.getMethod("getRecomposeScope");
            recordUsed = composer.getMethod("recordUsed", scope);
            invalidate = impl.getDeclaredMethod("invalidateForResult", Object.class);
            valid = impl.getDeclaredMethod("getValid");
            invalidate.setAccessible(true);
            valid.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            recomposeScope = null; recordUsed = null; invalidate = null; valid = null;
            Probe.log("QS_LIVE_REFRESH_MISSING", "error=" + e.getClass().getSimpleName());
        }
    }

    private static Method named(Class<?> c, String name) {
        if (c == null) return null;
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return m;
        return null;
    }

    /** LSPosed's XposedBridge.deoptimizeMethod(Member); not in the API 82 stubs, so optional. */
    private static void deoptimize(Member... members) {
        Method deopt;
        try { deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class); }
        catch (NoSuchMethodException | RuntimeException e) { Probe.log("QS_DEOPT_MISSING", "count=" + members.length); return; }
        for (Member m : members) {
            if (m == null) { Probe.log("QS_DEOPT_SKIPPED", "member=missing"); continue; }
            try { deopt.invoke(null, m); }
            catch (ReflectiveOperationException | RuntimeException e) { Probe.error("QS_DEOPT_FAILED", e); }
        }
    }

    /** Proves which panel the device shows, and hands its root and expansion to the edge light. */
    private void hookPanel() {
        Class<?> panel = resolve(PANEL);
        if (panel == null) return;
        try {
            XposedBridge.hookMethod(panel.getDeclaredMethod("attachComposeView"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Probe.log("QS_COMPOSE_ACTIVE", "optIn=" + ConfigClient.flag(ProductFlags.QUICK_SETTINGS)
                            + " master=" + master.getAsBoolean());
                    edge.panel(p.thisObject);
                }
            });
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            Probe.error("QS_PANEL_HOOK_FAILED", e);
        }
    }

    /**
     * Slider track and fill: BidirectionalBarKt draws both over the whole inner box. Inset the draw
     * area on every side (DrawTransform.inset's two steps: translate the canvas, shrink the size;
     * getTransform() is stripped by R8) and clip it to a pill, then restore both after the call.
     */
    private void hookSlider() {
        Class<?> bar = resolve(BAR);
        Method draw = named(bar, BAR_DRAW);
        if (draw == null) { Probe.log("QS_SLIDER_MISSING", "method=" + BAR_DRAW); return; }
        try {
            Class<?> scope = Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope", false, loader);
            drawContext = scope.getMethod("getDrawContext");
            Class<?> context = drawContext.getReturnType();
            contextSize = context.getMethod("getSize-NH-jbRc");
            setContextSize = context.getMethod("setSize-uvyYCjk", long.class);
            contextCanvas = context.getMethod("getCanvas");
            nativeCanvas = Class.forName("androidx.compose.ui.graphics.AndroidCanvas", false, loader)
                    .getDeclaredField("internalCanvas");
            nativeCanvas.setAccessible(true);
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { insetSlider(p); }
                @Override protected void afterHookedMethod(MethodHookParam p) { restoreSlider(p); }
            });
            Probe.log("QS_SLIDER_READY", "method=" + draw.toGenericString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.error("QS_SLIDER_HOOK_FAILED", e);
        }
    }

    private static final class SliderInset {
        final Object context; final android.graphics.Canvas canvas; final long size; final int save;
        SliderInset(Object context, android.graphics.Canvas canvas, long size, int save) {
            this.context = context; this.canvas = canvas; this.size = size; this.save = save;
        }
    }

    private void insetSlider(XC_MethodHook.MethodHookParam p) {
        if (!enabled()) return;
        try {
            Object context = drawContext.invoke(p.args[0]);
            Object canvas = contextCanvas.invoke(context);
            Object raw = nativeCanvas.getDeclaringClass().isInstance(canvas) ? nativeCanvas.get(canvas) : null;
            if (!(raw instanceof android.graphics.Canvas)) return;
            android.graphics.Canvas c = (android.graphics.Canvas) raw;
            long size = (Long) contextSize.invoke(context);
            float w = Float.intBitsToFloat((int) (size >>> 32)), h = Float.intBitsToFloat((int) size);
            float m = QuickSettingsGlass.sliderInset(w, h);
            if (m <= 0f || w <= 2f * m || h <= 2f * m) return;
            float iw = w - 2f * m, ih = h - 2f * m, r = Math.min(iw, ih) / 2f;
            // Registered before anything changes: whatever fails below, the after-hook (which runs
            // even when Samsung's draw throws) or the catch undoes exactly what was done.
            SliderInset undo = new SliderInset(context, c, size, c.save());
            p.setObjectExtra("oulgSlider", undo);
            try {
                c.translate(m, m);
                android.graphics.Path pill = new android.graphics.Path();
                pill.addRoundRect(0f, 0f, iw, ih, r, r, android.graphics.Path.Direction.CW);
                // Samsung's track is a plain rectangle rounded only by the outer clip: keep it a pill.
                c.clipPath(pill);
                setContextSize.invoke(context, ((long) Float.floatToRawIntBits(iw) << 32)
                        | (Float.floatToRawIntBits(ih) & 0xffffffffL));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                restoreSlider(p);
                throw e;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** Runs whether or not Samsung's draw threw, so the canvas and size are always restored. */
    private void restoreSlider(XC_MethodHook.MethodHookParam p) {
        Object extra = p.getObjectExtra("oulgSlider");
        if (!(extra instanceof SliderInset)) return;
        p.setObjectExtra("oulgSlider", null); // Once: the catch above and the after-hook may both call.
        SliderInset s = (SliderInset) extra;
        // The canvas first and unconditionally: a size that cannot be put back must not leave the
        // translation and clip on the rest of the frame.
        s.canvas.restoreToCount(s.save);
        try {
            setContextSize.invoke(s.context, s.size);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private void beforeBox(Object[] a) {
        pending.remove();
        try {
            bootstrap();
            if (broken) return;
            // Even with the master or the option off, the border constructor captures this box's
            // own scope (see capture), so that switching either on later applies live.
            pending.set(new Pending(a[7], 0L, 0L, null, null, 0f));
            if (!enabled()) return;
            // Bits 4-32 of the $default mask: Samsung's defaults replace the width/colour/shape
            // arguments, so what is read here would not be what is drawn. Bit 2 (outlined, default
            // true) is how the tile group container calls it, and is read as true below.
            if (!(a[9] instanceof Integer) || ((Integer) a[9] & 0x3c) != 0) { log("defaults", a); return; }
            boolean outlinedDefault = ((Integer) a[9] & OUTLINED_DEFAULT) != 0;
            long outline = (Long) a[3], background = (Long) a[4];
            // Only sRGB colours (colour space id 0) are understood; others stay native.
            if (!QuickSettingsGlass.srgb(outline) || !QuickSettingsGlass.srgb(background)) { log("non-srgb", a); return; }
            boolean circle = a[5] == circleShape;
            Kind kind = QuickSettingsGlass.kind(circle, QuickSettingsGlass.argb(background), QuickSettingsGlass.argb(outline));
            log(kind.name(), a);
            if (kind == Kind.NONE) return;
            boolean dark = dark();
            long fill = QuickSettingsGlass.color(QuickSettingsGlass.fill(kind, QuickSettingsGlass.argb(background), dark));
            boolean outlined = (outlinedDefault || Boolean.TRUE.equals(a[1]))
                    && a[2] instanceof Float && (Float) a[2] > 0f;
            boolean rimmed = outlined && QuickSettingsGlass.rimmed(kind);
            // The inner box is padded by the outline width only when the outline is on.
            pending.set(new Pending(a[7], background, fill, rimmed ? rim(dark) : null,
                    kind == Kind.CONTAINER ? a[5] : null, outlined ? (Float) a[2] : 0f));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** A Quick-Settings-only session may never see a notification row: start the provider here. */
    private void bootstrap() {
        if (configStarted) return;
        try {
            Object current = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (!(current instanceof android.content.Context)) return;
            app = (android.content.Context) current;
            configStarted = true;
            startConfig.accept(app);
        } catch (ReflectiveOperationException | RuntimeException e) {
            configStarted = true;
            Probe.error("QS_CONFIG_BOOTSTRAP_FAILED", e);
        }
    }

    /**
     * Runs inside BoxWithOutline's body (its border constructor), where the composer's current
     * scope is the box's own restart group, not its caller's: invalidating a caller could skip the
     * box when Samsung's arguments are unchanged. Marked used so endRestartGroup keeps it; its
     * restart then executes the body with the forced bit and the hooks run again.
     */
    private void capture(Object composer) {
        if (recomposeScope == null || composer == null) return;
        try {
            Object scope = recomposeScope.invoke(composer);
            if (scope == null) return;
            recordUsed.invoke(composer, scope);
            synchronized (scopes) { scopes.add(scope); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            recomposeScope = null;
            Probe.error("QS_SCOPE_CAPTURE_FAILED", e);
        }
    }

    private Object rim(boolean dark) throws ReflectiveOperationException {
        Object cached = dark ? darkRim : lightRim;
        if (cached != null) return cached;
        Object brush = solidColor.newInstance(QuickSettingsGlass.color(QuickSettingsGlass.rim(dark)));
        if (dark) darkRim = brush; else lightRim = brush;
        return brush;
    }

    /** Samsung recomposes its colours on a theme change, and this is read again then. */
    private boolean dark() {
        if (app == null) return true;
        int mode = app.getResources().getConfiguration().uiMode;
        return (mode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Called on the ConfigProvider callback (main thread), including when the master switch changes. */
    public void refresh() {
        if (!ready) return;
        boolean on = enabled();
        if (on == applied) return;
        applied = on;
        invalidateAll(on ? "on" : "off");
    }

    private void invalidateAll(String reason) {
        if (invalidate == null) {
            Probe.log("QS_COMPOSE_REFRESH", "reason=" + reason + " live=false (applies on the next composition)");
            return;
        }
        List<Object> live;
        synchronized (scopes) { live = new ArrayList<>(scopes); }
        int count = 0;
        for (Object scope : live) {
            try {
                if (!Boolean.TRUE.equals(valid.invoke(scope))) continue;
                invalidate.invoke(scope, (Object) null);
                count++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Probe.error("QS_COMPOSE_REFRESH_FAILED", e);
                return;
            }
        }
        Probe.log("QS_COMPOSE_REFRESH", "reason=" + reason + " scopes=" + count);
    }

    /** Fail closed: stop recolouring and let every captured box recompose with Samsung's values. */
    private void fail(Throwable e) {
        pending.remove();
        if (broken) return;
        broken = true;
        Probe.error("QS_COMPOSE_FAILED", e);
        applied = false;
        invalidateAll("failure");
    }

    private void log(String kind, Object[] a) {
        String key = kind + " circle=" + (a[5] == circleShape) + " outlined=" + a[1] + " mask=" + a[9] + " width=" + a[2]
                + " outline=" + hex((Long) a[3]) + " background=" + hex((Long) a[4]);
        synchronized (logged) {
            // Compose may compose off the main thread (prefetch); the set is small and bounded.
            if (logged.size() >= MAX_LOGGED || !logged.add(key)) return;
        }
        Probe.log("QS_BOX", key);
    }

    private static String hex(long c) {
        return QuickSettingsGlass.srgb(c) ? Integer.toHexString(QuickSettingsGlass.argb(c)) : "cs" + (c & 0x3f) + ":" + Long.toHexString(c);
    }
}

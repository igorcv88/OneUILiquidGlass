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
import io.github.igorcv88.oneuiliquidglass.glass.QsStyle;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
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
    private static final String ICON_TILE = "com.android.systemui.samsung.quicksetting.ui.tiles.IconTextTileKt";
    private static final String ICON_TILE_METHOD = "IconTextTile-v3dQN_U";
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
    private final QsBackdrop backdrop;
    private final QsContentHooks content;
    private volatile QsStyle style = QsStyle.read();
    private volatile Object pillShape;
    private final ThreadLocal<Boolean> barComposition = new ThreadLocal<>();
    private Object circleShape;
    private Method recomposeScope, recordUsed, invalidate, valid;
    private Constructor<?> solidColor;
    private Object darkRim, lightRim;
    private boolean ready, broken, configStarted, applied;
    private volatile boolean iconLogged;
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
        boolean slider;
        Kind kind = Kind.NONE;
        Object sliderShape;
        float outer = 1f;
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
        this.edge = new QuickSettingsEdgeLight(loader, this::enabled,
                () -> ConfigClient.flag(ProductFlags.QUICK_SETTINGS), master);
        this.backdrop = new QsBackdrop(this::enabled, () -> { darkRim=null; lightRim=null; invalidateAll("backdrop"); });
        this.content = new QsContentHooks(loader, this::enabled, () -> style, backdrop::tone, backdrop::headerTone,
                () -> pending.get()==null ? Kind.NONE : pending.get().kind, this::capture);
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
                    if (q.sliderShape != null) p.args[2] = q.sliderShape;
                    if (q.rim != null && p.args[0] instanceof Float && (Float) p.args[0] > 0f) p.args[1] = q.rim;
                }
            });
            XposedBridge.hookMethod(backgroundInit, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Pending q = pending.get();
                    if (q == null || q.fillDone) {
                        QsContentHooks.Frame f = content.current();
                        if (f == null || !f.media || f.background || f.shape == null || !enabled()) return;
                        f.background = true;
                        capture(f.composer);
                        long original = (Long)p.args[0];
                        if (!QuickSettingsGlass.srgb(original)) return;
                        if ((QuickSettingsGlass.argb(original) >>> 24) == 0) return;
                        q = new Pending(f.composer, original, QuickSettingsGlass.color(QuickSettingsGlass.fill(
                                Kind.CONTAINER, QuickSettingsGlass.argb(original), dark(), backdrop.tone(), style)), null, f.shape, 0);
                    }
                    if (!(p.args[0] instanceof Long) || (Long) p.args[0] != q.background) return;
                    q.fillDone = true;
                    p.args[0] = q.fill;
                    if (q.sliderShape != null) p.args[3] = q.sliderShape;
                    if (q.edgeShape != null) p.setObjectExtra("oulgEdge", q);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object q = p.getObjectExtra("oulgEdge");
                    if (q instanceof Pending) edge.element(p.thisObject, ((Pending) q).edgeShape, ((Pending) q).edgeBorder, ((Pending) q).outer);
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
            hookIconTile();
            content.install();
            installPrefetchGesture();
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
                    Object view = Reflect.read(p.thisObject, "view");
                    if (view instanceof android.view.View) backdrop.panel((android.view.View)view);
                }
            });
            Probe.log("QS_EDGE_STATUS", "phase=panel-hook installed=true acquisition=attachComposeView");
        } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
            Probe.error("QS_PANEL_HOOK_FAILED", e);
        }
    }

    /**
     * Slider track and fill: BidirectionalBarKt draws both over the whole inner box. Inset the draw
     * area on every side (DrawTransform.inset's two steps: translate the canvas, shrink the size;
     * getTransform() is stripped by R8) and clip it to a pill, then restore both after the call.
     */
    private void installPrefetchGesture() {
        try {
            Class<?> receiver=Class.forName("android.view.InputEventReceiver");
            XposedBridge.hookAllMethods(receiver,"dispatchInputEvent",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(!enabled()||p.args.length<2||!(p.args[1] instanceof android.view.MotionEvent))return;
                    android.view.MotionEvent e=(android.view.MotionEvent)p.args[1];
                    if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN && e.getRawY()<200)backdrop.prefetch();
                }
            });
        } catch(ClassNotFoundException|RuntimeException|LinkageError e){Probe.error("QS_PREFETCH_GESTURE_FAILED",e);}
    }

    private void hookSlider() {
        Class<?> bar=resolve(BAR);if(bar==null)return;
        try { new QsSliderHooks(loader,this::enabled,()->style,()->pillShape!=null||style.outer>=1,
                on->{if(on)barComposition.set(true);else barComposition.remove();},this::capture).install(bar); }
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_SLIDER_HOOK_FAILED",e);}
    }

    /**
     * Icon position of DeviceControl-type tiles (QuickSettingsGlass.iconLayout): only IconTextTile's
     * start and gap arguments change, recognised by the values QuickTile derives from Samsung's
     * isNoBgTile flag, never by tile spec, provider or position.
     */
    private void hookIconTile() {
        Method tile = null;
        Class<?> c = resolve(ICON_TILE);
        if (c != null) for (Method m : c.getDeclaredMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (m.getName().equals(ICON_TILE_METHOD) && t.length == 14 && t[4] == float.class
                    && t[5] == float.class && t[6] == float.class
                    && t[12].getName().equals("androidx.compose.runtime.Composer")) tile = m;
        }
        if (tile == null) { Probe.log("QS_ICON_TILE_MISSING", "method=" + ICON_TILE_METHOD); return; }
        try {
            XposedBridge.hookMethod(tile, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (broken || !(p.args[4] instanceof Float) || !(p.args[5] instanceof Float)) return;
                    float[] layout = QuickSettingsGlass.iconLayout((Float) p.args[4], (Float) p.args[5]);
                    if (layout == null) return;
                    // The caller's scope (QuickTile's tile content): its restart passes the
                    // arguments again, so switching the option applies live.
                    capture(p.args[12]);
                    if (!enabled()) return;
                    p.args[4] = layout[0];
                    p.args[5] = layout[1];
                    if (!iconLogged) { iconLogged = true; Probe.log("QS_ICON_TILE", "noDisc start=6.0 gap=0.0 -> start=Unspecified gap=" + layout[1]); }
                }
            });
            Probe.log("QS_ICON_TILE_READY", "method=" + tile.toGenericString());
        } catch (RuntimeException | LinkageError e) {
            Probe.error("QS_ICON_TILE_HOOK_FAILED", e);
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
            long fill = QuickSettingsGlass.color(QuickSettingsGlass.fill(kind, QuickSettingsGlass.argb(background), dark, backdrop.tone(), style));
            boolean outlined = (outlinedDefault || Boolean.TRUE.equals(a[1]))
                    && a[2] instanceof Float && (Float) a[2] > 0f;
            boolean rimmed = outlined && QuickSettingsGlass.rimmed(kind);
            // The inner box is padded by the outline width only when the outline is on.
            pending.set(new Pending(a[7], background, fill, rimmed ? rim(dark) : null,
                    kind == Kind.CONTAINER ? a[5] : null, outlined ? (Float) a[2] : 0f));
            pending.get().slider = kind == Kind.CONTAINER && Boolean.TRUE.equals(barComposition.get());
            pending.get().kind = kind;
            if (pending.get().slider) {
                pending.get().sliderShape = pillShape;
                pending.get().outer = pillShape != null ? style.outer : 1f;
            }
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
        Object brush = solidColor.newInstance(QuickSettingsGlass.color(QuickSettingsGlass.rim(dark, backdrop.tone(), style)));
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
        QsStyle next = QsStyle.read();
        boolean valuesChanged = !next.equals(style);
        if (valuesChanged) {
            darkRim=null; lightRim=null;
            try { pillShape=next.outer < 1f ? QsPillShape.create(loader, next.outer) : null; }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) { pillShape=null; Probe.error("QS_PILL_SHAPE_FAILED", e); }
            edge.configure(next.edgeBlur);
        }
        style = next;
        if (on != applied) backdrop.configuration();
        if (valuesChanged && on == applied) { edge.check(); invalidateAll("values"); return; }
        // Include effective tuning even if only a compositor gate changed (no Compose toggle).
        if (on == applied) { edge.check(); return; }
        edge.status(on ? "on" : "off");
        applied = on;
        invalidateAll(on ? "on" : "off");
    }

    private void invalidateAll(String reason) {
        content.invalidateArtwork();
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
                synchronized (scopes) { scopes.remove(scope); }
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

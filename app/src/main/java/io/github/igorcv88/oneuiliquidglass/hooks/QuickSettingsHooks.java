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
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
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
    private final Set<String> tileSurfaces = new HashSet<>();
    private final Set<String> tileDecisions = new HashSet<>();
    private final QsTileProbe tileProbe = new QsTileProbe();
    private final QuickSettingsEdgeLight edge;
    private final QsBackdrop backdrop;
    private final QsContentHooks content;
    private volatile QsStyle style = QsStyle.read();
    /** The notifications' edge optics, drawn inside every glass container (QsCapsuleOptics). */
    private final QsCapsuleOptics capsuleOptics = new QsCapsuleOptics();
    private volatile Object pillShape;
    /**
     * BidirectionalBarKt.PillShape, the shape only the slider passes to BoxWithOutline. Matched by
     * identity: a box recomposing on its own (a live refresh invalidates its scope) does not run
     * inside BidirectionalBar, so a call-stack flag would lose the slider there.
     */
    private volatile Object barPill;
    private Object circleShape;
    private Method recomposeScope, recordUsed, invalidate, valid;
    private Constructor<?> solidColor;
    private Object darkRim, lightRim, darkLitRim, lightLitRim;
    /** Boxes whose own optics drew: only their outline is attenuated (QsLitRims). */
    private final QsLitRims litRims = new QsLitRims();
    private boolean ready, broken, configStarted, applied;
    private volatile boolean iconLogged;
    private long configRevision;
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
        boolean dark;
        /** A glass container's shape and outline width (dp), for its edge light; null for circles. */
        final Object edgeShape;
        final float edgeBorder;
        boolean slider;
        QsOpticsCoverage.Token token;
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
        this.configRevision = ConfigClient.revision();
        this.edge = new QuickSettingsEdgeLight(loader, this::enabled,
                () -> ConfigClient.flag(ProductFlags.QUICK_SETTINGS), master);
        // After a drag the released edge regions are rebuilt by the containers' next draw.
        edge.onRestore(() -> invalidateAll("drag-end"));
        // Optics gone (shader or geometry failure): full-strength rims again, recomposed now.
        capsuleOptics.onFailure(() -> { litRims.clear(); clearRims(); invalidateAll("optics-failed"); });
        edge.onOptics(this::opticsResult);
        edge.optics(capsuleOptics, () -> QuickSettingsHooks.this.backdrop != null
                && QuickSettingsHooks.this.backdrop.tone() == io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.LIGHT_SCENE);
        BackdropEvents.add(() -> QuickSettingsHooks.this.backdrop.event());
        this.backdrop = new QsBackdrop(this::enabled, edge::expansion, () -> { clearRims(); invalidateAll("backdrop"); },
                () -> { QsContentHooks c = QuickSettingsHooks.this.content; if (c != null) c.invalidateDraw(); });
        this.content = new QsContentHooks(loader, this::enabled, () -> style, backdrop::tone, backdrop::headerTone, this::ink,
                this::capture, tileProbe);
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
                    Object box = capture(q.composer);
                    if (q.sliderShape != null) p.args[2] = q.sliderShape;
                    if (q.rim != null && p.args[0] instanceof Float && (Float) p.args[0] > 0f) {
                        // Attenuated only for a box whose own optics already drew; otherwise full.
                        boolean lit = litRims.lit(box) && QsCapsuleOptics.available();
                        Object brush = q.rim;
                        if (lit) try { brush = rim(q.dark, true); }
                        catch (ReflectiveOperationException | RuntimeException e) { lit = false; Probe.error("QS_RIM_FAILED", e); }
                        p.args[1] = brush;
                        if (q.token != null) {
                            q.token.box = box == null ? null : new java.lang.ref.WeakReference<>(box);
                            q.token.rimAttenuated = lit;
                        }
                    }
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
                                Kind.CONTAINER, QuickSettingsGlass.argb(original), backdrop.tone(), style)), null, f.shape, 0);
                    }
                    if (!(p.args[0] instanceof Long) || (Long) p.args[0] != q.background) return;
                    if (q.token == null && q.edgeShape != null) q.token = edge.selected("media-active");
                    q.fillDone = true;
                    p.args[0] = q.fill;
                    if (q.sliderShape != null) p.args[3] = q.sliderShape;
                    if (q.edgeShape != null) p.setObjectExtra("oulgEdge", q);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object q = p.getObjectExtra("oulgEdge");
                    if (q instanceof Pending) edge.element(p.thisObject, ((Pending) q).edgeShape, ((Pending) q).edgeBorder, ((Pending) q).outer, ((Pending) q).token);
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
            tileProbe.install(loader);
            content.install();
            installPrefetchGesture();
            ready = true;
            Tuning.onChanged(() -> {
                if (!ready || !enabled()) return;
                Probe.log("QS_TUNING_APPLY", "generation=" + Tuning.generation
                        + " revision=" + ConfigClient.revision() + " visiblePaint=unverified");
                invalidateAll("tuning");
            });
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
    /**
     * The touch that opens the panel starts at the top edge: sampling from its ACTION_DOWN gives the
     * capture a head start on Samsung's animation. Every SystemUI input event passes here, so the
     * cheap tests come first; QsBackdrop.gesture() ignores it while the panel is open or a fresh
     * sample exists, so a tap on the status bar or header does not capture again.
     */
    private void installPrefetchGesture() {
        try {
            Class<?> receiver=Class.forName("android.view.InputEventReceiver");
            XposedBridge.hookAllMethods(receiver,"dispatchInputEvent",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(p.args.length<2||!(p.args[1] instanceof android.view.MotionEvent))return;
                    android.view.MotionEvent e=(android.view.MotionEvent)p.args[1];
                    int action=e.getActionMasked();
                    if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL){
                        touching=false;
                        // The drag that hid the panel's edge regions is over: they come back with it.
                        edge.suppress(false);
                        return;
                    }
                    if(action!=android.view.MotionEvent.ACTION_DOWN)return;
                    touching=true;
                    if(e.getRawY()>=topEdge()||!enabled())return;
                    backdrop.gesture();
                }
            });
        } catch(ClassNotFoundException|RuntimeException|LinkageError e){Probe.error("QS_PREFETCH_GESTURE_FAILED",e);}
    }

    private int topEdgePx;
    /** A finger is down on SystemUI (main thread input). */
    private volatile boolean touching;

    /**
     * Where a pull-down of the panel starts: 64 dp from the top, about twice One UI's status bar
     * (its height is an internal resource, not read here).
     */
    private int topEdge() {
        if (topEdgePx <= 0)
            topEdgePx = Math.round(64f * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return topEdgePx;
    }

    private void hookSlider() {
        Class<?> bar=resolve(BAR);if(bar==null)return;
        try {
            Field pill=bar.getDeclaredField("PillShape");
            pill.setAccessible(true);
            barPill=pill.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            barPill=null;
            Probe.log("QS_SLIDER_SHAPE_MISSING", "error=" + e.getClass().getSimpleName());
        }
        try {
            QsSliderHooks sliders = new QsSliderHooks(loader,this::enabled,()->style,()->pillShape!=null||style.outer>=1,
                    this::capture,()->touching,()->edge.suppress(true),backdrop::tone);
            sliders.onInteraction(edge::suppress);
            sliders.install(bar);
        }
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
            Kind discKind = circle ? QuickSettingsGlass.tileDiscKind(kind,
                    QuickSettingsGlass.argb(background),QuickSettingsGlass.argb(outline),
                    tileProbe.currentModel()!=null) : kind;
            if (circle) tileProbe.disc(tileProbe.currentModel(), discKind);
            log(kind.name(), a);
            if (Probe.trace) {
                String key = "kind=" + kind + " backdrop=" + backdrop.tone()
                        + " circle=" + circle + " shape=" + (a[5] == null ? "null" : a[5].getClass().getName())
                        + " bg=" + hex(background) + " outline=" + hex(outline)
                        + " outlined=" + (a[1] instanceof Boolean ? a[1] : "unknown")
                        + " mask=" + a[9]
                        + " tileModel=" + tileProbe.currentId()
                        + " composeOwner=" + (content.current() == null ? "unbound" : content.current().owner);
                synchronized (tileSurfaces) {
                    if (tileSurfaces.size() < 96 && tileSurfaces.add(key))
                        Probe.log("QS_TILE_SURFACE_COMPOSE", key + " state=unverified");
                }
            }
            // A disc is recoloured only inside a verified QuickTile owner, and only for known
            // ACTIVE/IDLE states. NONE includes Samsung's intentionally disc-less controls.
            if (kind != Kind.CONTAINER) {
                if (circle) {
                    Object model = tileProbe.currentModel();
                    int nativeColor = QuickSettingsGlass.argb(background);
                    int mapped = model == null ? nativeColor
                            : style.tileBackground(discKind, backdrop.tone(), nativeColor);
                    if (mapped != nativeColor) {
                        a[4] = QuickSettingsGlass.color(mapped);
                        // Theme Park off may give idle discs a separate outline; clearing only
                        // the fill left a gray ring around the supposedly backgroundless icon.
                        if (discKind == Kind.IDLE && mapped == 0)
                            a[3] = QuickSettingsGlass.color(QuickSettingsGlass.CLEAR);
                        if (Probe.trace) Probe.log("QS_TILE_COLOR", "model=" + tileProbe.currentId()
                                + " kind=" + discKind + " nativeKind=" + kind + " backdrop=" + backdrop.tone()
                                + " native=" + Integer.toHexString(nativeColor)
                                + " applied=" + Integer.toHexString(mapped));
                    }
                    if (Probe.trace) {
                        String reason = model == null ? "unbound" : discKind == Kind.NONE ? "no-disc"
                                : backdrop.tone() == QsScene.Tone.UNKNOWN ? "tone-unknown"
                                : mapped != nativeColor ? "applied" : "native-or-disabled";
                        String key = "reason=" + reason + " kind=" + discKind + " tone=" + backdrop.tone()
                                + " owner=" + (content.current() == null ? "unbound" : content.current().owner)
                                + " model=" + tileProbe.currentId();
                        synchronized (tileDecisions) {
                            if (tileDecisions.size() < 96 && tileDecisions.add(key))
                                Probe.log("QS_TILE_COLOR_DECISION", key);
                        }
                    }
                }
                return;
            }
            boolean dark = dark();
            // Containers: transparent, or the faint dark tint over a light backdrop. Discs: Samsung's.
            long fill = QuickSettingsGlass.color(QuickSettingsGlass.fill(kind, QuickSettingsGlass.argb(background), backdrop.tone(), style));
            boolean outlined = (outlinedDefault || Boolean.TRUE.equals(a[1]))
                    && a[2] instanceof Float && (Float) a[2] > 0f;
            boolean rimmed = outlined && QuickSettingsGlass.rimmed(kind);
            // The inner box is padded by the outline width only when the outline is on.
            pending.set(new Pending(a[7], background, fill, rimmed ? rim(dark, false) : null,
                    kind == Kind.CONTAINER ? a[5] : null, outlined ? (Float) a[2] : 0f));
            pending.get().dark = dark;
            pending.get().slider = kind == Kind.CONTAINER && barPill != null && a[5] == barPill;
            pending.get().kind = kind;
            QsContentHooks.Frame owner = content.current();
            pending.get().token = edge.selected(pending.get().slider ? "slider"
                    : owner != null && owner.media ? owner.emptyMedia ? "media-empty" : "media-active" : "container");
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
    private Object capture(Object composer) {
        if (recomposeScope == null || composer == null) return null;
        try {
            Object scope = recomposeScope.invoke(composer);
            if (scope == null) return null;
            recordUsed.invoke(composer, scope);
            synchronized (scopes) { scopes.add(scope); }
            return scope;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            recomposeScope = null;
            Probe.error("QS_SCOPE_CAPTURE_FAILED", e);
            return null;
        }
    }

    /**
     * The outline brush. Full strength ({@code lit} false) unless this box's own optics drew:
     * border and background of one box are built in the same restart scope, which is what ties
     * the outline to the background node that drew (QsLitRims), not the shader's availability.
     */
    private Object rim(boolean dark, boolean lit) throws ReflectiveOperationException {
        Object cached = lit ? (dark ? darkLitRim : lightLitRim) : (dark ? darkRim : lightRim);
        if (cached != null) return cached;
        Object brush = solidColor.newInstance(QuickSettingsGlass.color(QuickSettingsGlass.basalRim(
                QuickSettingsGlass.rim(dark, backdrop.tone(), style), lit)));
        if (lit) { if (dark) darkLitRim = brush; else lightLitRim = brush; }
        else if (dark) darkRim = brush; else lightRim = brush;
        return brush;
    }

    private void clearRims() { darkRim = null; lightRim = null; darkLitRim = null; lightLitRim = null; }

    /**
     * Draw thread, from the background node's optics: the box whose state changed (full outline
     * to attenuated, or back) recomposes on the main thread; nothing else does.
     */
    private void opticsResult(QsOpticsCoverage.Token token, boolean drawn, long session) {
        java.lang.ref.WeakReference<Object> ref = token.box;
        Object box = ref == null ? null : ref.get();
        if (!litRims.report(box, drawn, session)) return;
        if (Probe.trace) Probe.log("QS_RIM_OWNER", "session=" + session + " id=" + token.id + " role=" + token.role
                + " rim=" + (drawn ? "attenuated" : "full"));
        if (invalidate == null) return;
        java.lang.ref.WeakReference<Object> weak = new java.lang.ref.WeakReference<>(box);
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Object scope = weak.get();
                if (scope == null) return;
                try { if (Boolean.TRUE.equals(valid.invoke(scope))) invalidate.invoke(scope, (Object) null); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError e) { Probe.error("QS_RIM_REFRESH_FAILED", e); }
            });
        } catch (RuntimeException | LinkageError e) { Probe.error("QS_RIM_REFRESH_FAILED", e); }
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
        long revision = ConfigClient.revision();
        boolean newRevision = revision != configRevision;
        configRevision = revision;
        boolean on = enabled();
        QsStyle next = QsStyle.read();
        boolean valuesChanged = !next.equals(style);
        if (valuesChanged) {
            clearRims();
            try { pillShape=next.outer < 1f ? QsPillShape.create(loader, next.outer) : null; }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) { pillShape=null; Probe.error("QS_PILL_SHAPE_FAILED", e); }
            edge.configure(next.edgeBlur);
        }
        style = next;
        if (on != applied) backdrop.configuration();
        if (valuesChanged && on == applied) {
            edge.check();
            Probe.log("QS_CONFIG_RECEIVED", "revision=" + revision + " styleChanged=true enabled=" + on);
            content.invalidateDraw();
            invalidateAll("values");
            return;
        }
        // Include effective tuning even if only a compositor gate changed (no Compose toggle).
        if (on == applied) {
            edge.check();
            if (newRevision) {
                // A label/ink/material preference is not part of QsStyle. Previously such a
                // change waited for a Samsung recomposition or a backdrop transition.
                Probe.log("QS_CONFIG_RECEIVED", "revision=" + revision + " styleChanged=false enabled=" + on);
                invalidateAll("config");
            }
            return;
        }
        edge.status(on ? "on" : "off");
        applied = on;
        Probe.log("QS_CONFIG_RECEIVED", "revision=" + revision + " enabled=" + on + " gateChanged=true");
        invalidateAll(on ? "on" : "off");
    }

    /**
     * Ink level for QS text at draw time (true: the header's). The panel's own decision while it is
     * open; otherwise the header follows the notification shade's decision (shadeHeader): the
     * clock and date shown over the notification shade are this same Compose header, which kept
     * white over a white app while only the QS backdrop could darken it (device, 28/10; the
     * legacy QSClockPanelView the shade controller recoloured is not the one on screen).
     */
    Float ink(Boolean header) {
        boolean h = Boolean.TRUE.equals(header);
        Float own = enabled() ? backdrop.ink(h) : null;
        if (!h || (own != null && backdrop.open())) return own;
        float shade = shadeHeaderInk;
        return Float.isNaN(shade) ? own : shade;
    }

    /** The notification shade's header decision (true: light backdrop, dark ink); null when it closes. */
    private volatile float shadeHeaderInk = Float.NaN;
    private android.animation.ValueAnimator shadeHeaderAnimator;

    /** Main thread, from HeadsUpHooks. A change between two known tones moves smoothly; anything else at once. */
    public void shadeHeader(Boolean light) {
        float target = light == null ? Float.NaN : light ? 1f : 0f, from = shadeHeaderInk;
        if (Float.compare(target, from) == 0) return;
        if (shadeHeaderAnimator != null) { shadeHeaderAnimator.cancel(); shadeHeaderAnimator = null; }
        // The scene already changed: interpolation would retain unsafe ink for 120 ms.
        shadeHeaderInk = target;
        Probe.log("QS_HEADER_INK", "source=shade target=" + (light == null ? "native" : light ? "dark" : "light")
                + " animated=false");
        content.invalidateDraw();
    }

    private void invalidateAll(String reason) {
        content.invalidateDraw();
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

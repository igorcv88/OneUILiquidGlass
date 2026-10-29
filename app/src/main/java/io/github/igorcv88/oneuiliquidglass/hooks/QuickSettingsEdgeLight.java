package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;

/**
 * Backdrop-dependent edge light for the Quick Settings containers, drawn by the compositor
 * (sfhook profile 3, debug.oulg.sf.optics >= 10). The module's Compose path recolours Samsung's
 * containers but has no pixels of what is behind the shade window; the compositor has them. Each
 * glass container gets a blur region tagged for profile 3, which the rewritten shader turns into
 * light entering from the outline (deeper the brighter the backdrop right behind it) and, from
 * library v0.27 (optics 11), a refraction of the backdrop over the outline's bevel; past the bevel
 * the region is transparent. The module's basal rim and veil stay as they are, so without the
 * compositor (APK only) nothing changes.
 *
 * <p>The region is drawn from the container's own background node (BackgroundNode.draw), inside
 * Compose's display list: its RenderNode follows every layout, scroll and transform of the
 * container. It covers the container's outer box, the background's inner box grown by Samsung's
 * outline width, with the outer box's corner radius, so the light band lies on the rim Compose
 * draws. What the region does not follow is alpha (a blur region ignores the alpha of whoever
 * draws it): the panel's fade is the expansion fraction and its ancestors' view alpha, read before
 * each frame of the window; a scaled ancestor removes the light, since the tag needs whole pixels.</p>
 *
 * <p>Lifecycle: a BackgroundBlurDrawable stays in its ViewRootImpl's aggregator (a strong set) while
 * it is visible with a non-zero alpha and radius, even after its RenderNode stops being drawn (it
 * then sends an empty rect). So every region is released (BackgroundBlurBridge.release: radius 0,
 * invisible, alpha 0) when its node is detached, rebound to another element, replaced, when the
 * effect is switched off, and when a hook fails. A transient fade keeps alpha >= 1 instead: a
 * zero-alpha drawable skips its draw, and the region would stay missing when the panel fades back
 * in without this display list being recorded again.</p>
 */
final class QuickSettingsEdgeLight {
    private static final int MIN_OPTICS = 10;
    /** Containers recoloured in one composition are bound in the same frame; more is a leak. */
    private static final int MAX_PENDING_ELEMENTS = 256;
    private final ClassLoader loader;
    private final BooleanSupplier enabled, option, master;
    private final QuickSettingsEdgeDiagnostics diagnostic =
            new QuickSettingsEdgeDiagnostics(s -> Probe.log("QS_EDGE_STATUS", s));
    /**
     * BackgroundElements the recolouring built for a glass container. By identity: the element's
     * equals() compares colour, brush, alpha and shape, and every container's background is the
     * same veil on a RectangleShape, so a hash map would merge them all (and match any equal
     * background elsewhere in SystemUI).
     */
    private final List<Spec> elements = new ArrayList<>();
    /** Background nodes currently drawing one of those elements (Modifier.Node: identity equals). */
    private final Map<Object, Edge> nodes = new WeakHashMap<>();
    private WeakReference<View> host = new WeakReference<>(null);
    private Object adapter;
    private Method getSize, getDensity, drawContext, contextCanvas, toPx, flowValue;
    private Field nativeCanvas, topStart, expansionField;
    private ViewTreeObserver.OnPreDrawListener preDraw;
    private ViewTreeObserver observed;
    private int alpha = 255;
    private int blurPx = QuickSettingsGlass.EDGE_BLUR_PX;
    /** Last expansion read (-1: none, or unreadable), for the log; 0 = closed, 1 = open is assumed. */
    private float lastExpansion = -1f;
    private boolean ready, logged, compositorBroken, visible;
    private long hostGeneration;
    private final QsOpticsCoverage coverage = new QsOpticsCoverage((event, detail) -> {
        if (!event.equals("QS_OPTICS_PATH") || Probe.trace) Probe.log(event, detail);
    });
    synchronized QsOpticsCoverage.Token selected(String role) { return coverage.select(role); }
    private final ThreadLocal<DrawFrame> drawing = new ThreadLocal<>();
    /** LayoutNodeDrawScope.drawContent, when hooked, places optics between background and content. */
    private boolean contentBoundary;
    private static final class DrawFrame {
        final Object node, scope;
        boolean consumed;
        DrawFrame(Object node, Object scope) { this.node=node; this.scope=scope; }
    }
    private float ancestorAlpha, edgeFade;
    private boolean shown, scaled;
    /**
     * A slider is being dragged: Samsung fades every other control with a Compose layer alpha,
     * which a blur region does not see, so their edge regions stayed on screen as empty glass
     * outlines over the home screen (device screenshot, 28/10). While suppressed every region is
     * released (radius 0, invisible, alpha 0: gone from the compositor, not just dimmed) and no
     * draw creates one; when the finger lifts, {@link #restored} recomposes the containers, whose
     * next draw builds the regions again (the same path as switching the effect on).
     */
    private boolean suppressed;
    private Runnable restored = () -> {};
    void onRestore(Runnable r) { restored = r == null ? () -> {} : r; }
    /** Whether regions may exist now (tests and diagnostics). */
    synchronized boolean suppressed() { return suppressed; }
    private View.OnAttachStateChangeListener attachListener;
    private Runnable settledSnapshot;

    /** A recoloured container background: its element, its shape and Samsung's outline width (dp). */
    private static final class Spec {
        final WeakReference<Object> element;
        final Object shape;
        final float border, outer;
        final QsOpticsCoverage.Token token;
        Spec(Object element, Object shape, float border, float outer, QsOpticsCoverage.Token token) {
            this.token = token;
            this.element = new WeakReference<>(element); this.shape = shape; this.border = border; this.outer = outer;
        }
    }

    private final class Edge {
        final Spec spec;
        BackgroundBlurBridge bridge;
        Object root;
        float tag = -1f;
        boolean drawn;
        Edge(Spec spec) { this.spec = spec; }
        void release() {
            if (bridge != null) {
                bridge.release();
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.REGION_RELEASED))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.REGION_RELEASED, "");
            }
            bridge = null; root = null; tag = -1f; drawn = false;
        }
    }

    QuickSettingsEdgeLight(ClassLoader loader, BooleanSupplier enabled,
            BooleanSupplier option, BooleanSupplier master) {
        this.loader = loader;
        this.enabled = enabled;
        this.option = option;
        this.master = master;
    }

    synchronized void install(Class<?> backgroundElement) {
        String member = "Compose classes";
        try {
            Class<?> node = Class.forName("androidx.compose.foundation.BackgroundNode", false, loader);
            Class<?> scope = Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope", false, loader);
            Class<?> density = Class.forName("androidx.compose.ui.unit.Density", false, loader);
            Class<?> cornerSize = Class.forName("androidx.compose.foundation.shape.CornerSize", false, loader);
            Class<?> cornerShape = Class.forName("androidx.compose.foundation.shape.CornerBasedShape", false, loader);
            Class<?> nodeBase = Class.forName("androidx.compose.ui.Modifier$Node", false, loader);
            member = "DrawScope.getSize-NH-jbRc";
            getSize = scope.getMethod("getSize-NH-jbRc");
            member = "Density.getDensity";
            getDensity = density.getMethod("getDensity");
            member = "DrawScope.getDrawContext";
            drawContext = scope.getMethod("getDrawContext");
            member = "DrawContext.getCanvas";
            contextCanvas = drawContext.getReturnType().getMethod("getCanvas");
            member = "CornerSize.toPx-TmRCtEA";
            toPx = cornerSize.getMethod("toPx-TmRCtEA", density, long.class);
            member = "CornerBasedShape.topStart";
            topStart = cornerShape.getDeclaredField("topStart");
            topStart.setAccessible(true);
            member = "AndroidCanvas.internalCanvas";
            nativeCanvas = Class.forName("androidx.compose.ui.graphics.AndroidCanvas", false, loader)
                    .getDeclaredField("internalCanvas");
            nativeCanvas.setAccessible(true);
            member = "BackgroundElement.create";
            Class<?> contentScope = Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope", false, loader);
            QsNodeAbi abi = QsNodeAbi.resolve(backgroundElement, node, contentScope);
            Method create = abi.create, update = abi.update, draw = abi.draw;
            member = "Modifier.Node.markAsDetached$ui";
            Method detach = nodeBase.getDeclaredMethod("markAsDetached$ui");
            // BackgroundNode.draw paints the background then invokes this concrete drawContent.
            // Intercept only that exact scope/frame, before it enters descendants.
            // Optional: if absent/inlined, draw a bevel-only overlay after BackgroundNode.draw.
            // Drawing at the start can be hidden by Samsung's background and falsely report success.
            Method drawContent = null;
            try {
                drawContent = Class.forName("androidx.compose.ui.node.LayoutNodeDrawScope", false, loader)
                        .getDeclaredMethod("drawContent");
            } catch (ReflectiveOperationException | LinkageError e) {
                Probe.log("QS_HOOK_STATUS", "family=optics step=drawContent ready=false fallback=after-node-rim error="
                        + e.getClass().getSimpleName());
            }
            try {
                Class<?> drawNode = Class.forName("androidx.compose.ui.node.DrawModifierNode", false, loader);
                invalidateDraw = Class.forName("androidx.compose.ui.node.DrawModifierNodeKt", false, loader)
                        .getMethod("invalidateDraw", drawNode);
            } catch (ReflectiveOperationException | LinkageError e) { invalidateDraw = null; }
            member = "Xposed hooks";
            XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { bind(p.thisObject, p.getResult()); }
            });
            XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { bind(p.thisObject, p.args[0]); }
            });
            XposedBridge.hookMethod(detach, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { unbind(p.thisObject); }
            });
            boolean boundaryHooked = false;
            if (drawContent != null) try {
                XposedBridge.hookMethod(drawContent, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        DrawFrame f = drawing.get();
                        if (f == null || f.consumed || f.scope != p.thisObject) return;
                        f.consumed = true;
                        optics(f.node, f.scope);
                    }
                });
                boundaryHooked = true;
            } catch (RuntimeException | LinkageError ex) {
                Probe.log("QS_HOOK_STATUS", "family=optics step=drawContent ready=false fallback=after-node-rim error="
                        + ex.getClass().getSimpleName());
            }
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("qsEdgePrevious", drawing.get());
                    if (nodes.containsKey(p.thisObject)) drawing.set(new DrawFrame(p.thisObject, p.args[0]));
                    else drawing.remove();
                    draw(p.thisObject, p.args[0]);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    DrawFrame f = drawing.get();
                    if (f != null && !f.consumed) {
                        if (!p.hasThrowable()) {
                            // Guaranteed to be above Samsung's background. The optics draw clips
                            // itself to the bevel so a late draw cannot cover centre labels.
                            f.consumed = true;
                            optics(f.node, f.scope, true);
                        } else {
                            Edge e = nodes.get(f.node);
                            if (e != null) result(e.spec.token, false, "background-draw-threw");
                        }
                    }
                    DrawFrame old = (DrawFrame) p.getObjectExtra("qsEdgePrevious");
                    if (old == null) drawing.remove(); else drawing.set(old);
                }
            });
            // Prevent an inlined drawContent from bypassing the optical layer boundary.
            try { XposedBridge.class.getMethod("deoptimizeMethod", java.lang.reflect.Member.class).invoke(null, draw); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
            contentBoundary = boundaryHooked;
            ready = true;
            Probe.log("QS_HOOK_STATUS", "family=optics step=" + (contentBoundary ? "drawContent" : "after-node-rim")
                    + " ready=true method=" + (contentBoundary ? drawContent.toGenericString() : draw.toGenericString()));
            Probe.log("QS_EDGE_READY", "draw=" + draw.toGenericString());
            Probe.log("QS_EDGE_STATUS", "phase=install ready=true host=" + (host.get() != null));
            active();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (e instanceof ClassNotFoundException) member = e.getMessage();
            Probe.log("QS_EDGE_STATUS", "phase=install ready=false member=" + member
                    + " error=" + e.getClass().getSimpleName());
            Probe.log("QS_OPTICS_STATUS", "ready=false step=" + member);
            Probe.error("QS_EDGE_INSTALL_FAILED", e);
        }
    }

    /** The recolouring built this element for a glass container of this shape and outline width. */
    synchronized void element(Object element, Object shape, float borderDp) { element(element, shape, borderDp, 1f); }

    synchronized void element(Object element, Object shape, float borderDp, float outer) {
        element(element, shape, borderDp, outer, selected("unknown"));
    }

    synchronized void element(Object element, Object shape, float borderDp, float outer, QsOpticsCoverage.Token token) {
        if (token == null) token = selected("unknown");
        if (!ready || shape == null) { coverage.stage(token, "fallback", "not-ready-or-shape"); return; }
        coverage.stage(token, "registered", "element=" + identity(element));
        purge();
        if (elements.size() >= MAX_PENDING_ELEMENTS) {
            elements.remove(0);
            if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.PENDING_EVICTED))
                diagnostic.event(QuickSettingsEdgeDiagnostics.Event.PENDING_EVICTED, "limit=" + MAX_PENDING_ELEMENTS);
        }
        elements.add(new Spec(element, shape, borderDp, outer, token));
        if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.ELEMENT))
            diagnostic.event(QuickSettingsEdgeDiagnostics.Event.ELEMENT, "element=" + identity(element));
    }

    private void purge() {
        for (Iterator<Spec> it = elements.iterator(); it.hasNext(); ) if (it.next().element.get() == null) it.remove();
    }

    /** The spec registered for this element, taken off the list: an element is applied to one node. */
    private Spec take(Object element) {
        for (Iterator<Spec> it = elements.iterator(); it.hasNext(); ) {
            Spec s = it.next();
            if (s.element.get() == element) { it.remove(); return s; }
        }
        return null;
    }

    /** SecQSPanelCompose.attachComposeView: the panel root and its expansion state. */
    synchronized void panel(Object controller) {
        diagnostic.begin("panel-attached");
        Object view = Reflect.read(controller, "view");
        Object a = Reflect.read(controller, "composeAdapter");
        View old = host.get();
        if (old != view) {
            unwatch(old);
            closeRegions("host-replaced");
            nodes.clear(); elements.clear();
            hostGeneration++;
        }
        host = new WeakReference<>(view instanceof View ? (View) view : null);
        adapter = a;
        expansionField = null;
        flowValue = null;
        try {
            expansionField = a == null ? null : Reflect.field(a.getClass(), "expansionFraction");
            Object flow = expansionField == null ? null : expansionField.get(a);
            flowValue = flow == null ? null : flow.getClass().getMethod("getValue");
        } catch (ReflectiveOperationException | RuntimeException e) {
            flowValue = null;
        }
        Probe.log("QS_EDGE_PANEL", "host=" + (view instanceof View) + " expansion=" + (flowValue != null));
        Probe.log("QS_EDGE_STATUS", "phase=panel afterInstall=" + ready + " host=" + (view instanceof View)
                + " view=" + identity(view) + " adapter=" + (a != null)
                + " expansionReadable=" + (flowValue != null));
        if (view instanceof View) {
            View v = (View) view;
            if (old != view || attachListener == null) {
                attachListener = new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { watch(v); }
                    @Override public void onViewDetachedFromWindow(View v) { detached(v); }
                };
                v.addOnAttachStateChangeListener(attachListener);
            }
            watch(v);
        }
        active();
        summary("snapshot");
    }

    private synchronized void bind(Object element, Object node) {
        if (node == null) return;
        Spec s = elements.isEmpty() ? null : take(element);
        Edge e = nodes.get(node);
        // Compose can call update twice with the same element (typed and erased bridges).
        if (s == null && e != null && e.spec.element.get() == element) return;
        if (s == null) {
            // Ordinary Compose backgrounds are not QS candidates. Only diagnose misses while a QS
            // spec is pending or when this node previously belonged to us.
            if ((!elements.isEmpty() || e != null)
                    && diagnostic.count(QuickSettingsEdgeDiagnostics.Event.ELEMENT_UNMATCHED))
                diagnostic.event(QuickSettingsEdgeDiagnostics.Event.ELEMENT_UNMATCHED,
                        "element=" + identity(element) + " node=" + identity(node) + " pending=" + elements.size());
            if (e != null) { releaseNode(e); nodes.remove(node); }
            return;
        }
        if (e != null && e.spec == s) return;
        if (e != null) releaseNode(e);
        nodes.put(node, new Edge(s));
        coverage.stage(s.token, "bound", "element=" + identity(element) + " node=" + identity(node));
        if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.BIND))
            diagnostic.event(QuickSettingsEdgeDiagnostics.Event.BIND,
                    "element=" + identity(element) + " node=" + identity(node));
    }

    private synchronized void unbind(Object node) {
        Edge e = nodes.isEmpty() ? null : nodes.remove(node);
        if (e != null) releaseNode(e);
    }

    private void releaseNode(Edge e) {
        e.release();
        if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.NODE_RELEASED))
            diagnostic.event(QuickSettingsEdgeDiagnostics.Event.NODE_RELEASED, "");
    }

    /** Runs on every pre-draw while inactive: no copy, and release() does not touch the map. */
    private void releaseAll() {
        for (Edge e : nodes.values()) if (e.bridge != null) e.release();
    }

    /** Compositor edge light wanted and possible: the option, the compositor and its profile 3. */
    private boolean active() {
        Tuning t = Tuning.get();
        boolean on = enabled.getAsBoolean();
        diagnostic.gate(ready, option.getAsBoolean(), master.getAsBoolean(), on,
                t.sfRefract, t.sfOptics, host.get() != null);
        return ready && !compositorBroken && on && t.sfRefract && t.sfOptics >= MIN_OPTICS && host.get() != null;
    }

    private synchronized void draw(Object node, Object scope) {
        Edge e = nodes.isEmpty() ? null : nodes.get(node);
        if (e == null) {
            if (!elements.isEmpty() && diagnostic.count(QuickSettingsEdgeDiagnostics.Event.UNBOUND_NODE))
                diagnostic.event(QuickSettingsEdgeDiagnostics.Event.UNBOUND_NODE, "pending=" + elements.size());
            return;
        }
        // The media artwork already occupies the container: an independent SF blur contour
        // would create a second, wide halo. Its AGSL hairline follows the artwork exactly.
        if (!usesCompositor(e.spec.token.role)) { e.release(); return; }
        if (!active() || suppressed || fade() <= 0f) { e.release(); return; }
        alpha = edgeAlpha(edgeFade);
        if (alpha == 0) { e.release(); return; }
        View v = host.get();
        try {
            long size = (Long) getSize.invoke(scope);
            int w = Math.round(Float.intBitsToFloat((int) (size >>> 32))), h = Math.round(Float.intBitsToFloat((int) size));
            if (w <= 0 || h <= 0) {
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.INVALID_SIZE))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.INVALID_SIZE, "width=" + w + " height=" + h);
                e.release(); return;
            }
            // Samsung pads the background's box by the outline width (Compose rounds it to px).
            int out = QuickSettingsGlass.edgeOutset(e.spec.border, (Float) getDensity.invoke(scope));
            int ow = w + 2 * out, oh = h + 2 * out;
            long outer = ((long) Float.floatToRawIntBits(ow) << 32) | (Float.floatToRawIntBits(oh) & 0xffffffffL);
            Object corner = topStart.get(e.spec.shape);
            float radius = corner == null ? -1f : (Float) toPx.invoke(corner, scope, outer);
            float[] envelope = QuickSettingsGlass.envelope(ow, oh, e.spec.outer);
            if (e.spec.outer < 1f) radius = Math.min(envelope[2]-envelope[0], envelope[3]-envelope[1])/2f;
            float tag = QuickSettingsGlass.edgeTag(radius);
            if (tag < 0f) {
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.INVALID_RADIUS_TAG))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.INVALID_RADIUS_TAG,
                            "width=" + ow + " height=" + oh + " radius=" + radius + " tag=" + tag + " eligible=false");
                e.release(); return;
            }
            Object canvas = contextCanvas.invoke(drawContext.invoke(scope));
            Object raw = nativeCanvas.getDeclaringClass().isInstance(canvas) ? nativeCanvas.get(canvas) : null;
            if (!(raw instanceof android.graphics.Canvas) || !((android.graphics.Canvas) raw).isHardwareAccelerated()) {
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.NONHARDWARE_CANVAS))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.NONHARDWARE_CANVAS, "");
                e.release(); return;
            }
            Object root = Reflect.call(v, "getViewRootImpl");
            if (root == null) {
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.MISSING_VIEWROOT))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.MISSING_VIEWROOT, "");
                e.release(); return;
            }
            if (e.bridge == null || e.root != root) {
                e.release();
                try {
                    e.bridge = BackgroundBlurBridge.create(v);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
                    if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.BLUR_FACTORY_FAILED))
                        diagnostic.event(QuickSettingsEdgeDiagnostics.Event.BLUR_FACTORY_FAILED,
                                "error=" + ex.getClass().getSimpleName());
                    throw ex;
                }
                e.root = root;
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.REGION_CREATED))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.REGION_CREATED,
                            "node=" + identity(node) + " drawable=" + identity(e.bridge.drawable));
            }
            if (tag != e.tag) {
                float[] radii = {tag, tag, tag, tag, tag, tag, tag, tag};
                // No region colour: the shader draws the light and the refracted bevel; past it, nothing.
                e.bridge.update(blurPx, 0, radii);
                e.tag = tag;
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.REGION_UPDATED))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.REGION_UPDATED,
                            "width=" + ow + " height=" + oh + " radius=" + radius + " tag=" + tag);
            }
            watch(v);
            e.bridge.drawable.setAlpha(alpha);
            // The inner box grown back to the outer one: the RenderNode reports these bounds whatever
            // the inner clip, and the light band then sits on Samsung's outline.
            e.bridge.draw((android.graphics.Canvas) raw, new Rect(Math.round(-out+envelope[0]), Math.round(-out+envelope[1]),
                    Math.round(-out+envelope[2]), Math.round(-out+envelope[3])));
            if (!e.drawn) {
                e.drawn = true;
                if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.REGION_DRAWN))
                    diagnostic.event(QuickSettingsEdgeDiagnostics.Event.REGION_DRAWN, "alphaSent=" + e.bridge.drawable.getAlpha());
            }
            observe(true);
            if (!logged) {
                logged = true;
                Probe.log("QS_EDGE_REGION", "size=" + ow + "x" + oh + " border=" + out + " radius=" + radius + " tag=" + tag
                        + " alpha=" + alpha + " lightScene=" + lightScene.getAsBoolean()
                        + " sceneGain=" + sceneGain() + " expansion=" + lastExpansion);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            fail(ex);
        }
    }

    private void fail(Throwable ex) {
        // A compositor failure must not disable the independent APK optical material.
        compositorBroken = true;
        releaseAll();
        Probe.error("QS_EDGE_FAILED", ex);
        status("failure");
    }

    /** Before each frame of the window: the fade from the expansion and the ancestors, to every region. */
    private synchronized void watch(View v) {
        ViewTreeObserver o = v.getViewTreeObserver();
        if (preDraw != null && observed == o && o.isAlive()) return;
        if (observed != null && observed.isAlive() && preDraw != null)
            observed.removeOnPreDrawListener(preDraw);
        if (preDraw == null) preDraw = () -> {
            try { refresh(); } catch (RuntimeException | LinkageError e) { fail(e); }
            return true;
        };
        observed = o;
        o.addOnPreDrawListener(preDraw);
        refresh();
    }

    /** Before each frame of the shade window. */
    private synchronized void refresh() { refresh(false); }

    /**
     * Switched off (option, master, compositor): release, do not keep a residual alpha. A later
     * switch on recomposes the containers (QuickSettingsHooks.refresh), which draws them again.
     * The listener stays on the shade window from panel() on, also with the option off (its
     * default), so an inactive frame only checks the gate: the ancestor walk and the reflective
     * expansion read run only for a snapshot (toggle, config callback, failure).
     */
    private void refresh(boolean snapshot) {
        boolean sf = active();
        boolean on = enabled.getAsBoolean() && ready;
        if (!on && !snapshot) { closeRegions("disabled"); return; }
        float f = fade();
        boolean now = on && edgeAlpha(f) > 0;
        if (!now) { closeRegions("hidden-or-zero-fade"); observe(false); return; }
        if (!visible) {
            visible = true;
            coverage.begin();
            for (Edge e : nodes.values()) {
                e.spec.token.attempted = false; e.spec.token.drawn = false;
                coverage.adopt(e.spec.token);
            }
            for (Spec spec : elements) coverage.adopt(spec.token);
            redrawNodes();
        }
        alpha = edgeAlpha(f);
        if (!sf || suppressed) releaseAll();
        else for (Edge e : nodes.values()) if (e.bridge != null) e.bridge.drawable.setAlpha(alpha);
        observe(false);
    }

    private void closeRegions(String reason) {
        int before = 0;
        for (Edge e : nodes.values()) if (e.bridge != null) before++;
        releaseAll();
        alpha = 0;
        if (visible || before > 0) {
            coverage.summary("exit");
            Probe.log("QS_EDGE_EXIT", "session=" + coverage.session() + " reason=" + reason
                    + " regionsBefore=" + before + " regionsAfter=0 alphaPositiveAfter=0");
        }
        visible = false;
        cancelSettled(host.get());
    }

    private float fade() {
        View v = host.get();
        shown = v != null && v.isShown();
        scaled = false;
        ancestorAlpha = 0f;
        edgeFade = 0f;
        lastExpansion = -1f;
        if (!shown) return 0f;
        float a = 1f;
        for (Object x = v; x instanceof View; x = ((View) x).getParent()) {
            View view = (View) x;
            // A scaled region reaches the compositor at a fractional size: the tag would not hold.
            if (view.getScaleX() != 1f || view.getScaleY() != 1f) scaled = true;
            a *= view.getAlpha() * view.getTransitionAlpha();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) break;
        }
        // An expansion that cannot be read is not taken as "open": no dynamic light rather than one
        // that may outlive the panel's fade.
        float expansion = readExpansion();
        lastExpansion = expansion;
        ancestorAlpha = a;
        edgeFade = scaled || expansion < 0f ? 0f : QuickSettingsGlass.edgeFade(a, expansion);
        return edgeFade;
    }

    /**
     * SecQSPanelComposeAdapter.expansionFraction (0 closed, 1 open), -1 before panel() or when it
     * cannot be read. Also the opening signal of the backdrop sampling (QsBackdrop): the compose
     * root may stay VISIBLE while the panel is collapsed.
     */
    synchronized float expansion() { return readExpansion(); }

    private float readExpansion() {
        try {
            Object flow = expansionField == null || adapter == null ? null : expansionField.get(adapter);
            Object value = flow == null || flowValue == null ? null : flowValue.invoke(flow);
            return value instanceof Float ? (Float) value : -1f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1f;
        }
    }

    private QsCapsuleOptics optics;
    static boolean usesCompositor(String role) { return role == null || !role.startsWith("media"); }
    private BooleanSupplier lightScene = () -> false;
    private DoubleSupplier lightGain = () -> 1d;
    /** The capsules' glass optics (APK-side, with or without the compositor). */
    void optics(QsCapsuleOptics o, BooleanSupplier light) {
        optics(o, light, () -> 1d);
    }

    /** Scene-specific light gain; legacy callers retain neutral gain through the overload. */
    void optics(QsCapsuleOptics o, BooleanSupplier light, DoubleSupplier gain) {
        optics = o;
        lightScene = light == null ? () -> false : light;
        lightGain = gain == null ? () -> 1d : gain;
    }

    private float sceneGain() {
        if (!lightScene.getAsBoolean()) return 1f;
        double gain = lightGain.getAsDouble();
        return Double.isFinite(gain) ? (float) Math.max(0d, Math.min(2d, gain)) : 1f;
    }

    private int edgeAlpha(float visibility) {
        return Math.round(255f * Math.max(0f, Math.min(1f, visibility * sceneGain())));
    }

    /** Told whether a box's optics drew (token, drawn, panel session): the per-capsule outline follows it. */
    interface OpticsResult { void drawn(QsOpticsCoverage.Token token, boolean drawn, long session); }
    private volatile OpticsResult opticsResult = (t, d, s) -> {};
    void onOptics(OpticsResult r) { opticsResult = r == null ? (t, d, s) -> {} : r; }

    private void result(QsOpticsCoverage.Token token, boolean drawn, String reason) {
        coverage.stage(token, drawn ? "drawn" : "fallback", reason);
        try { opticsResult.drawn(token, drawn, coverage.session()); }
        catch (RuntimeException | LinkageError ex) { Probe.error("QS_RIM_OWNER_FAILED", ex); }
    }

    /** Draws the optics into a glass container's inner box; any failure leaves the capsule as it is. */
    private void optics(Object node, Object scope) { optics(node, scope, false); }

    private void optics(Object node, Object scope, boolean rimOnlyOverlay) {
        QsCapsuleOptics o = optics;
        Edge e;
        boolean dragging;
        synchronized (this) { e = nodes.isEmpty() ? null : nodes.get(node); dragging = suppressed; }
        if (e == null) return;
        // A slider drag: Samsung fades every other capsule, and their light goes with their
        // compositor regions (suppress) instead of relying on the layer alpha alone. The dragged
        // slider's container keeps its optics. Not a failure: the outline state is left as it is.
        if (dragging && !e.spec.token.role.equals("slider")) return;
        if (o == null || !QsCapsuleOptics.available() || !enabled.getAsBoolean()) {
            coverage.stage(e.spec.token, "fallback", "disabled-or-shader"); return;
        }
        coverage.stage(e.spec.token, "attempt", rimOnlyOverlay ? "after-node-rim" : "drawContent");
        try {
            long size = (Long) getSize.invoke(scope);
            float w = Float.intBitsToFloat((int) (size >>> 32)), h = Float.intBitsToFloat((int) size);
            float density = (Float) getDensity.invoke(scope);
            Object canvas = contextCanvas.invoke(drawContext.invoke(scope));
            Object raw = nativeCanvas.getDeclaringClass().isInstance(canvas) ? nativeCanvas.get(canvas) : null;
            if (!(raw instanceof android.graphics.Canvas) || !((android.graphics.Canvas) raw).isHardwareAccelerated()) {
                result(e.spec.token, false, "no-hardware-canvas"); return;
            }
            int out = QuickSettingsGlass.edgeOutset(e.spec.border, density);
            long outer = ((long) Float.floatToRawIntBits(w + 2 * out) << 32) | (Float.floatToRawIntBits(h + 2 * out) & 0xffffffffL);
            Object corner = topStart.get(e.spec.shape);
            float radius = corner == null ? Math.min(w, h) / 2f : (Float) toPx.invoke(corner, scope, outer) - out;
            float x = 0f, y = 0f;
            if (e.spec.outer < 1f) {
                // A thinner slider capsule (qs.outer, QsPillShape): the optics follow its envelope,
                // a centred pill inside the native box, as the compositor region does.
                float[] env = QuickSettingsGlass.envelope(w, h, e.spec.outer);
                x = env[0]; y = env[1]; w = env[2] - env[0]; h = env[3] - env[1];
                radius = Math.min(w, h) / 2f;
            }
            boolean media = !usesCompositor(e.spec.token.role);
            boolean success = o.draw(node, (android.graphics.Canvas) raw, x, y, w, h, radius,
                    density, lightScene.getAsBoolean(), rimOnlyOverlay, media, sceneGain());
            boolean first = !e.spec.token.drawn;
            result(e.spec.token, success, success ? "agsl" : "shader-or-size");
            if (success && first && Probe.trace) Probe.log("QS_OPTICS_DRAWN", "session=" + coverage.session()
                    + " id=" + e.spec.token.id + " role=" + e.spec.token.role + " w=" + w + " h=" + h
                    + " radius=" + radius + " hardware=true layer="
                    + (rimOnlyOverlay ? "after-node-rim" : "drawContent")
                    + " light=" + lightScene.getAsBoolean());
            if (success && first && Probe.trace) {
                io.github.igorcv88.oneuiliquidglass.glass.Tuning tuning = io.github.igorcv88.oneuiliquidglass.glass.Tuning.get();
                Probe.log("QS_EDGE_LAYER_STACK", "session="+coverage.session()+" id="+e.spec.token.id
                        +" role="+e.spec.token.role+" composeOutline=true agsl="+success
                        +" compositor="+usesCompositor(e.spec.token.role)+" fallbackOverlay="+rimOnlyOverlay
                        +" lightScene="+lightScene.getAsBoolean()+" qsGlint="+tuning.qsGlint
                        +" liveSheen="+tuning.liveSheen+" outline="+tuning.lightOutline);
            }
            if (success && first && media) Probe.log("QS_MEDIA_EDGE_DRAWN", "session=" + coverage.session()
                    + " id=" + e.spec.token.id + " role=" + e.spec.token.role
                    + " insetDp=0 border=native-artwork-edge compositor=false");

        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            result(e.spec.token, false, "geometry");
            if (Probe.trace) Probe.error("QS_OPTICS_FAILED", "geometry id=" + e.spec.token.id, ex);
        }
    }

    synchronized void suppress(boolean on) {
        if (suppressed == on) return;
        suppressed = on;
        int released = 0;
        if (on) for (Edge e : nodes.values()) if (e.bridge != null) { e.release(); released++; }
        Probe.log("QS_EDGE_SUPPRESS", "session=" + coverage.session() + " trigger=slider dragging=" + on + " nodes=" + nodes.size() + " released=" + released);
        // Drag start: the faded capsules' cached display lists still hold their AGSL light; draw
        // them again without it (draw invalidation only, no recomposition mid-gesture).
        if (on) invalidateNodes();
        else if (enabled.getAsBoolean() && fade() > 0f) redrawNodes();
    }

    private int invalidateNodes() {
        int n = 0;
        if (invalidateDraw == null) return 0;
        for (Object node : new ArrayList<>(nodes.keySet())) {
            try { invalidateDraw.invoke(null, node); n++; }
            catch (ReflectiveOperationException | RuntimeException e) { invalidateDraw = null; Probe.error("QS_EDGE_REDRAW_FAILED", e); break; }
        }
        return n;
    }

    /** DrawModifierNodeKt.invalidateDraw: the background nodes' own draw runs again (null: unavailable). */
    private Method invalidateDraw;

    /** Rebuilds released regions: each background node draws again, which creates its region. */
    private void redrawNodes() {
        int n = invalidateNodes();
        // Without the draw invalidation, recompose the containers instead.
        if (invalidateDraw == null) try { restored.run(); }
        catch (RuntimeException | LinkageError e) { Probe.error("QS_EDGE_RESTORE_FAILED", e); }
        Probe.log("QS_EDGE_RESTORE", "session=" + coverage.session() + " nodes=" + nodes.size() + " invalidated=" + n + " recreated=0" + " recomposed=" + (invalidateDraw == null));
    }

    synchronized void configure(int blur) {
        if (blurPx == blur) return;
        blurPx = Math.max(1, Math.min(32, blur));
        try {
            for (Edge e : nodes.values()) if (e.bridge != null && e.tag >= 0f) {
                float t=e.tag;
                e.bridge.update(blurPx, 0, new float[]{t,t,t,t,t,t,t,t});
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { fail(e); }
    }

    /** Config callbacks with no material toggle still expose changes to effective compositor gates. */
    synchronized void check() { refresh(true); }

    /** Also called when there is no region: a failed gate must not make the diagnostics disappear. */
    synchronized void status(String reason) {
        cancelSettled(host.get());
        diagnostic.begin(reason);
        refresh(true);
        summary("snapshot");
    }

    private void observe(boolean postDraw) {
        diagnostic.fade(shown, scaled, ancestorAlpha, lastExpansion, edgeFade);
        int positiveDrawn = 0;
        for (Edge e : nodes.values())
            if (e.bridge != null && e.drawn && e.bridge.drawable.getAlpha() > 0 && edgeFade > 0f)
                positiveDrawn++;
        String phase = diagnostic.observe(shown, lastExpansion, positiveDrawn, postDraw);
        if (phase != null) {
            summary(phase);
            if (phase.equals("exit")) cancelSettled(host.get());
            if (phase.equals("settled")) scheduleSettled();
        }
    }

    /** One delayed snapshot, after the opening animation, rather than an alpha log per frame. */
    private void scheduleSettled() {
        View v = host.get();
        if (v == null) return;
        cancelSettled(v);
        long ticket = hostGeneration, session = coverage.session();
        settledSnapshot = () -> {
            synchronized (QuickSettingsEdgeLight.this) {
                settledSnapshot = null;
                if (host.get() != v || hostGeneration != ticket || coverage.session() != session || !visible) return;
                try {
                    refresh(true);
                    if (shown && lastExpansion >= 0.95f) summary("stabilized");
                } catch (RuntimeException | LinkageError e) { fail(e); }
            }
        };
        v.postDelayed(settledSnapshot, 350);
    }

    private void cancelSettled(View v) {
        if (v != null && settledSnapshot != null) v.removeCallbacks(settledSnapshot);
        settledSnapshot = null;
    }

    private void summary(String phase) {
        coverage.summary(phase);
        int regions = 0, positive = 0, drawn = 0, min = 256, max = 0;
        for (Edge e : nodes.values()) {
            if (e.bridge == null) continue;
            regions++;
            int sent = e.bridge.drawable.getAlpha();
            min = Math.min(min, sent); max = Math.max(max, sent);
            if (sent > 0) positive++;
            if (e.drawn && sent > 0 && edgeFade > 0f) drawn++;
        }
        purge();
        diagnostic.summary(phase, "nodesActive=" + nodes.size()
                + " pending=" + elements.size() + " regionsActive=" + regions
                + " alphaPositive=" + positive + " visibleCandidates=" + drawn
                + " alphaSentMin=" + (regions == 0 ? 0 : min) + " alphaSentMax=" + max
                + " alphaCalculated=" + Math.round(255f * edgeFade)
                + " host=" + identity(host.get()) + " shown=" + shown + " ancestorAlpha=" + ancestorAlpha
                + " scaled=" + scaled + " expansion=" + lastExpansion + " edgeFade=" + edgeFade
                + " expansionReadable=" + (flowValue != null));
    }

    private synchronized void detached(View v) {
        cancelSettled(v);
        closeRegions("detach"); hostGeneration++;
        suppressed = false;
        shown = false; lastExpansion = -1f; edgeFade = 0f;
        observe(false);
        summary("detach");
        if (observed != null && observed.isAlive() && preDraw != null)
            observed.removeOnPreDrawListener(preDraw);
        observed = null;
    }

    private void unwatch(View v) {
        cancelSettled(v);
        if (observed != null && observed.isAlive() && preDraw != null)
            observed.removeOnPreDrawListener(preDraw);
        observed = null;
        if (v != null && attachListener != null) v.removeOnAttachStateChangeListener(attachListener);
        attachListener = null;
    }

    private static String identity(Object value) {
        return value == null ? "none" : Integer.toHexString(System.identityHashCode(value));
    }
}

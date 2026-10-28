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
    private boolean ready, logged;
    private float ancestorAlpha, edgeFade;
    private boolean shown, scaled;
    private View.OnAttachStateChangeListener attachListener;
    private Runnable settledSnapshot;

    /** A recoloured container background: its element, its shape and Samsung's outline width (dp). */
    private static final class Spec {
        final WeakReference<Object> element;
        final Object shape;
        final float border, outer;
        Spec(Object element, Object shape, float border, float outer) {
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
            Method create = backgroundElement.getDeclaredMethod("create");
            member = "BackgroundElement.update";
            Method update = backgroundElement.getDeclaredMethod("update", nodeBase);
            // BackgroundNode does not override onDetach; markAsDetached$ui is the node leaving the tree.
            member = "Modifier.Node.markAsDetached$ui";
            Method detach = nodeBase.getDeclaredMethod("markAsDetached$ui");
            member = "BackgroundNode.draw";
            Method draw = null;
            for (Method m : node.getDeclaredMethods())
                if (m.getName().equals("draw") && m.getParameterCount() == 1) draw = m;
            if (draw == null) {
                Probe.log("QS_EDGE_STATUS", "phase=install ready=false member=" + member);
                return;
            }
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
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { draw(p.thisObject, p.args[0]); }
            });
            ready = true;
            Probe.log("QS_EDGE_READY", "draw=" + draw.toGenericString());
            Probe.log("QS_EDGE_STATUS", "phase=install ready=true host=" + (host.get() != null));
            active();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (e instanceof ClassNotFoundException) member = e.getMessage();
            Probe.log("QS_EDGE_STATUS", "phase=install ready=false member=" + member
                    + " error=" + e.getClass().getSimpleName());
            Probe.error("QS_EDGE_INSTALL_FAILED", e);
        }
    }

    /** The recolouring built this element for a glass container of this shape and outline width. */
    synchronized void element(Object element, Object shape, float borderDp) { element(element, shape, borderDp, 1f); }

    synchronized void element(Object element, Object shape, float borderDp, float outer) {
        if (!ready || shape == null) return;
        purge();
        if (elements.size() >= MAX_PENDING_ELEMENTS) {
            elements.remove(0);
            if (diagnostic.count(QuickSettingsEdgeDiagnostics.Event.PENDING_EVICTED))
                diagnostic.event(QuickSettingsEdgeDiagnostics.Event.PENDING_EVICTED, "limit=" + MAX_PENDING_ELEMENTS);
        }
        elements.add(new Spec(element, shape, borderDp, outer));
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
            releaseAll();
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
        return ready && on && t.sfRefract && t.sfOptics >= MIN_OPTICS && host.get() != null;
    }

    private synchronized void draw(Object node, Object scope) {
        Edge e = nodes.isEmpty() ? null : nodes.get(node);
        if (e == null) {
            if (!elements.isEmpty() && diagnostic.count(QuickSettingsEdgeDiagnostics.Event.UNBOUND_NODE))
                diagnostic.event(QuickSettingsEdgeDiagnostics.Event.UNBOUND_NODE, "pending=" + elements.size());
            return;
        }
        if (!active()) { e.release(); return; }
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
            e.bridge.drawable.setAlpha(Math.max(1, alpha));
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
                        + " alpha=" + alpha + " expansion=" + lastExpansion);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            fail(ex);
        }
    }

    private void fail(Throwable ex) {
        ready = false;
        releaseAll();
        nodes.clear();
        elements.clear();
        Probe.error("QS_EDGE_FAILED", ex);
        status("failure");
        unwatch(host.get());
    }

    /** Before each frame of the window: the fade from the expansion and the ancestors, to every region. */
    private synchronized void watch(View v) {
        ViewTreeObserver o = v.getViewTreeObserver();
        if (preDraw != null && observed == o && o.isAlive()) return;
        if (observed != null && observed.isAlive() && preDraw != null)
            observed.removeOnPreDrawListener(preDraw);
        if (preDraw == null) preDraw = () -> { refresh(); return true; };
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
        boolean on = active();
        if (!on) {
            releaseAll();
            if (snapshot) { fade(); observe(false); }
            return;
        }
        float fade = fade();
        int next = Math.round(255f * fade);
        if (next != alpha) {
            alpha = next;
            for (Edge e : nodes.values())
                if (e.bridge != null) e.bridge.drawable.setAlpha(Math.max(1, alpha));
        }
        observe(false);
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
        float expansion = -1f;
        try {
            Object flow = expansionField == null || adapter == null ? null : expansionField.get(adapter);
            Object value = flow == null || flowValue == null ? null : flowValue.invoke(flow);
            if (value instanceof Float) expansion = (Float) value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            expansion = -1f;
        }
        lastExpansion = expansion;
        ancestorAlpha = a;
        edgeFade = scaled || expansion < 0f ? 0f : QuickSettingsGlass.edgeFade(a, expansion);
        return edgeFade;
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
        settledSnapshot = () -> {
            synchronized (QuickSettingsEdgeLight.this) {
                settledSnapshot = null;
                if (host.get() != v) return;
                refresh(true);
                if (shown && lastExpansion >= 0.95f) summary("stabilized");
            }
        };
        v.postDelayed(settledSnapshot, 350);
    }

    private void cancelSettled(View v) {
        if (v != null && settledSnapshot != null) v.removeCallbacks(settledSnapshot);
        settledSnapshot = null;
    }

    private void summary(String phase) {
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
        releaseAll();
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

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
    private final BooleanSupplier enabled;
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
    /** Last expansion read (-1: none, or unreadable), for the log; 0 = closed, 1 = open is assumed. */
    private float lastExpansion = -1f;
    private boolean ready, logged;

    /** A recoloured container background: its element, its shape and Samsung's outline width (dp). */
    private static final class Spec {
        final WeakReference<Object> element;
        final Object shape;
        final float border;
        Spec(Object element, Object shape, float border) {
            this.element = new WeakReference<>(element); this.shape = shape; this.border = border;
        }
    }

    private static final class Edge {
        final Spec spec;
        BackgroundBlurBridge bridge;
        Object root;
        float tag = -1f;
        Edge(Spec spec) { this.spec = spec; }
        void release() {
            if (bridge != null) bridge.release();
            bridge = null; root = null; tag = -1f;
        }
    }

    QuickSettingsEdgeLight(ClassLoader loader, BooleanSupplier enabled) {
        this.loader = loader;
        this.enabled = enabled;
    }

    void install(Class<?> backgroundElement) {
        try {
            Class<?> node = Class.forName("androidx.compose.foundation.BackgroundNode", false, loader);
            Class<?> scope = Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope", false, loader);
            Class<?> density = Class.forName("androidx.compose.ui.unit.Density", false, loader);
            Class<?> cornerSize = Class.forName("androidx.compose.foundation.shape.CornerSize", false, loader);
            Class<?> cornerShape = Class.forName("androidx.compose.foundation.shape.CornerBasedShape", false, loader);
            Class<?> nodeBase = Class.forName("androidx.compose.ui.Modifier$Node", false, loader);
            getSize = scope.getMethod("getSize-NH-jbRc");
            getDensity = density.getMethod("getDensity");
            drawContext = scope.getMethod("getDrawContext");
            contextCanvas = drawContext.getReturnType().getMethod("getCanvas");
            toPx = cornerSize.getMethod("toPx-TmRCtEA", density, long.class);
            topStart = cornerShape.getDeclaredField("topStart");
            topStart.setAccessible(true);
            nativeCanvas = Class.forName("androidx.compose.ui.graphics.AndroidCanvas", false, loader)
                    .getDeclaredField("internalCanvas");
            nativeCanvas.setAccessible(true);
            Method create = backgroundElement.getDeclaredMethod("create");
            Method update = backgroundElement.getDeclaredMethod("update", nodeBase);
            // BackgroundNode does not override onDetach; markAsDetached$ui is the node leaving the tree.
            Method detach = nodeBase.getDeclaredMethod("markAsDetached$ui");
            Method draw = null;
            for (Method m : node.getDeclaredMethods())
                if (m.getName().equals("draw") && m.getParameterCount() == 1) draw = m;
            if (draw == null) { Probe.log("QS_EDGE_MISSING", "member=BackgroundNode.draw"); return; }
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.error("QS_EDGE_INSTALL_FAILED", e);
        }
    }

    /** The recolouring built this element for a glass container of this shape and outline width. */
    void element(Object element, Object shape, float borderDp) {
        if (!ready || shape == null) return;
        purge();
        if (elements.size() >= MAX_PENDING_ELEMENTS) elements.remove(0);
        elements.add(new Spec(element, shape, borderDp));
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
    void panel(Object controller) {
        Object view = Reflect.read(controller, "view");
        Object a = Reflect.read(controller, "composeAdapter");
        if (view instanceof View) host = new WeakReference<>((View) view);
        adapter = a;
        try {
            expansionField = a == null ? null : Reflect.field(a.getClass(), "expansionFraction");
            Object flow = expansionField == null ? null : expansionField.get(a);
            flowValue = flow == null ? null : flow.getClass().getMethod("getValue");
        } catch (ReflectiveOperationException | RuntimeException e) {
            flowValue = null;
        }
        Probe.log("QS_EDGE_PANEL", "host=" + (view instanceof View) + " expansion=" + (flowValue != null));
    }

    private void bind(Object element, Object node) {
        if (node == null) return;
        Spec s = elements.isEmpty() ? null : take(element);
        Edge e = nodes.get(node);
        if (s == null) { if (e != null) { e.release(); nodes.remove(node); } return; }
        if (e != null && e.spec == s) return;
        if (e != null) e.release();
        nodes.put(node, new Edge(s));
    }

    private void unbind(Object node) {
        Edge e = nodes.isEmpty() ? null : nodes.remove(node);
        if (e != null) e.release();
    }

    private void releaseAll() {
        for (Edge e : new ArrayList<>(nodes.values())) e.release();
    }

    /** Compositor edge light wanted and possible: the option, the compositor and its profile 3. */
    private boolean active() {
        if (!ready || !enabled.getAsBoolean()) return false;
        Tuning t = Tuning.get();
        return t.sfRefract && t.sfOptics >= MIN_OPTICS && host.get() != null;
    }

    private void draw(Object node, Object scope) {
        Edge e = nodes.isEmpty() ? null : nodes.get(node);
        if (e == null) return;
        if (!active()) { e.release(); return; }
        View v = host.get();
        try {
            long size = (Long) getSize.invoke(scope);
            int w = Math.round(Float.intBitsToFloat((int) (size >>> 32))), h = Math.round(Float.intBitsToFloat((int) size));
            if (w <= 0 || h <= 0) return;
            // Samsung pads the background's box by the outline width (Compose rounds it to px).
            int out = QuickSettingsGlass.edgeOutset(e.spec.border, (Float) getDensity.invoke(scope));
            int ow = w + 2 * out, oh = h + 2 * out;
            long outer = ((long) Float.floatToRawIntBits(ow) << 32) | (Float.floatToRawIntBits(oh) & 0xffffffffL);
            Object corner = topStart.get(e.spec.shape);
            float radius = corner == null ? -1f : (Float) toPx.invoke(corner, scope, outer);
            float tag = QuickSettingsGlass.edgeTag(radius);
            if (tag < 0f) { e.release(); return; }
            Object canvas = contextCanvas.invoke(drawContext.invoke(scope));
            Object raw = nativeCanvas.getDeclaringClass().isInstance(canvas) ? nativeCanvas.get(canvas) : null;
            if (!(raw instanceof android.graphics.Canvas) || !((android.graphics.Canvas) raw).isHardwareAccelerated()) return;
            Object root = Reflect.call(v, "getViewRootImpl");
            if (root == null) return;
            if (e.bridge == null || e.root != root) {
                e.release();
                e.bridge = BackgroundBlurBridge.create(v);
                e.root = root;
            }
            if (tag != e.tag) {
                float[] radii = {tag, tag, tag, tag, tag, tag, tag, tag};
                // No region colour: the shader draws the light and the refracted bevel; past it, nothing.
                e.bridge.update(QuickSettingsGlass.EDGE_BLUR_PX, 0, radii);
                e.tag = tag;
            }
            watch(v);
            e.bridge.drawable.setAlpha(Math.max(1, alpha));
            // The inner box grown back to the outer one: the RenderNode reports these bounds whatever
            // the inner clip, and the light band then sits on Samsung's outline.
            e.bridge.draw((android.graphics.Canvas) raw, new Rect(-out, -out, w + out, h + out));
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
    }

    /** Before each frame of the window: the fade from the expansion and the ancestors, to every region. */
    private void watch(View v) {
        ViewTreeObserver o = v.getViewTreeObserver();
        if (preDraw != null && observed == o && o.isAlive()) return;
        if (preDraw == null) preDraw = () -> { refresh(); return true; };
        observed = o;
        o.addOnPreDrawListener(preDraw);
        refresh();
    }

    private void refresh() {
        if (nodes.isEmpty()) return;
        // Switched off (option, master, compositor): release, do not keep a residual alpha. A later
        // switch on recomposes the containers (QuickSettingsHooks.refresh), which draws them again.
        if (!active()) { releaseAll(); return; }
        int next = Math.round(255f * fade());
        if (next == alpha) return;
        alpha = next;
        for (Edge e : new ArrayList<>(nodes.values()))
            if (e.bridge != null) e.bridge.drawable.setAlpha(Math.max(1, alpha));
    }

    private float fade() {
        View v = host.get();
        if (v == null || !v.isShown()) return 0f;
        float a = 1f;
        for (Object x = v; x instanceof View; x = ((View) x).getParent()) {
            View view = (View) x;
            // A scaled region reaches the compositor at a fractional size: the tag would not hold.
            if (view.getScaleX() != 1f || view.getScaleY() != 1f) return 0f;
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
        return expansion < 0f ? 0f : QuickSettingsGlass.edgeFade(a, expansion);
    }
}

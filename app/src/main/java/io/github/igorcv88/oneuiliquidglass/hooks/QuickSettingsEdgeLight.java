package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * light along the outline in proportion to the luma right behind it, and leaves transparent
 * elsewhere. The module's basal rim and veil stay as they are, so without the compositor (APK
 * only) nothing changes.
 *
 * <p>The region is drawn from the container's own background node (BackgroundNode.draw), inside
 * Compose's display list: its RenderNode follows every layout, scroll and transform of the
 * container. What it does not follow is alpha (a blur region ignores the alpha of whoever draws
 * it). The panel's fade is the expansion fraction and its ancestors' view alpha, both read before
 * each frame and applied to every region; a scaled ancestor removes the light, since the tag needs
 * whole pixels (as for the shade cards).</p>
 */
final class QuickSettingsEdgeLight {
    private static final int MIN_OPTICS = 10;
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    /** BackgroundElements the recolouring built for a glass container, with that container's shape. */
    private final Map<Object, Object> elements = new WeakHashMap<>();
    /** Background nodes currently drawing one of those elements. */
    private final Map<Object, Edge> nodes = new WeakHashMap<>();
    private WeakReference<View> host = new WeakReference<>(null);
    private Object adapter;
    private Method getSize, getDensity, drawContext, contextCanvas, toPx, flowValue;
    private Field nativeCanvas, topStart, expansionField;
    private ViewTreeObserver.OnPreDrawListener preDraw;
    private ViewTreeObserver observed;
    private int alpha = 255;
    /** Last expansion read (-1: none), for the log: its 0 = closed, 1 = open reading is assumed. */
    private float lastExpansion = -1f;
    private boolean ready, logged;

    private static final class Edge {
        final Object shape;
        BackgroundBlurBridge bridge;
        Object root;
        float tag = -1f;
        Edge(Object shape) { this.shape = shape; }
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
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { draw(p.thisObject, p.args[0]); }
            });
            ready = true;
            Probe.log("QS_EDGE_READY", "draw=" + draw.toGenericString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.error("QS_EDGE_INSTALL_FAILED", e);
        }
    }

    /** The recolouring built this element for a glass container of this shape. */
    void element(Object element, Object shape) {
        if (ready && shape != null) elements.put(element, shape);
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
        Object shape = elements.get(element);
        if (shape == null) { nodes.remove(node); return; }
        Edge e = nodes.get(node);
        if (e == null || e.shape != shape) nodes.put(node, new Edge(shape));
    }

    /** Compositor edge light wanted and possible: the option, the compositor and its profile 3. */
    private boolean active() {
        if (!ready || !enabled.getAsBoolean()) return false;
        Tuning t = Tuning.get();
        return t.sfRefract && t.sfOptics >= MIN_OPTICS && host.get() != null;
    }

    private void draw(Object node, Object scope) {
        Edge e = nodes.get(node);
        if (e == null || !active()) return;
        View v = host.get();
        try {
            long size = (Long) getSize.invoke(scope);
            int w = Math.round(Float.intBitsToFloat((int) (size >>> 32))), h = Math.round(Float.intBitsToFloat((int) size));
            if (w <= 0 || h <= 0) return;
            Object corner = topStart.get(e.shape);
            float radius = corner == null ? -1f : (Float) toPx.invoke(corner, scope, size);
            float tag = QuickSettingsGlass.edgeTag(radius);
            if (tag < 0f) return;
            Object canvas = contextCanvas.invoke(drawContext.invoke(scope));
            Object raw = nativeCanvas.getDeclaringClass().isInstance(canvas) ? nativeCanvas.get(canvas) : null;
            if (!(raw instanceof android.graphics.Canvas) || !((android.graphics.Canvas) raw).isHardwareAccelerated()) return;
            Object root = Reflect.call(v, "getViewRootImpl");
            if (root == null) return;
            if (e.bridge == null || e.root != root) {
                if (e.bridge != null) e.bridge.release();
                e.bridge = BackgroundBlurBridge.create(v);
                e.root = root;
                e.tag = -1f;
            }
            if (tag != e.tag) {
                float[] radii = {tag, tag, tag, tag, tag, tag, tag, tag};
                // No region colour: the shader draws the light; the body stays transparent.
                e.bridge.update(QuickSettingsGlass.EDGE_BLUR_PX, 0, radii);
                e.tag = tag;
            }
            watch(v);
            // Never 0: a zero-alpha drawable skips its draw, and the region would be missing when the
            // panel fades back in without this display list being recorded again.
            e.bridge.drawable.setAlpha(Math.max(1, alpha));
            e.bridge.draw((android.graphics.Canvas) raw, new Rect(0, 0, w, h));
            if (!logged) {
                logged = true;
                Probe.log("QS_EDGE_REGION", "size=" + w + "x" + h + " radius=" + radius + " tag=" + tag + " alpha=" + alpha
                        + " expansion=" + lastExpansion);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            ready = false;
            Probe.error("QS_EDGE_FAILED", ex);
        }
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
        int next = Math.round(255f * fade());
        if (next == alpha) return;
        alpha = next;
        for (Edge e : new ArrayList<>(nodes.values()))
            if (e.bridge != null) e.bridge.drawable.setAlpha(Math.max(1, alpha));
    }

    private float fade() {
        View v = host.get();
        if (v == null || !active() || !v.isShown()) return 0f;
        float a = 1f;
        for (Object x = v; x instanceof View; x = ((View) x).getParent()) {
            View view = (View) x;
            // A scaled region reaches the compositor at a fractional size: the tag would not hold.
            if (view.getScaleX() != 1f || view.getScaleY() != 1f) return 0f;
            a *= view.getAlpha() * view.getTransitionAlpha();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) break;
        }
        float expansion = 1f;
        try {
            Object flow = expansionField == null ? null : expansionField.get(adapter);
            Object value = flow == null || flowValue == null ? null : flowValue.invoke(flow);
            if (value instanceof Float) expansion = (Float) value;
            lastExpansion = expansion;
        } catch (ReflectiveOperationException | RuntimeException e) {
            expansion = 1f;
        }
        return QuickSettingsGlass.edgeFade(a, expansion);
    }
}

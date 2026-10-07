package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.diagnostics.WindowSurvey;
import io.github.igorcv88.oneuiliquidglass.glass.Backdrop;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.SemBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.GlassDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.GlassSpec;
import io.github.igorcv88.oneuiliquidglass.glass.CornerGeometry;

public final class HeadsUpHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String BACKGROUND = "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    private final ClassLoader loader;
    private volatile boolean enabled;
    private boolean configPending;
    private final WeakHashMap<View, State> states = new WeakHashMap<>();
    private final Set<Method> installed = Collections.newSetFromMap(new java.util.HashMap<>());
    private Class<?> rowClass;
    private Field backgroundField;
    private boolean drawHook;
    private boolean shadeHook;
    private boolean blurEnvironmentReported;
    private final boolean samsungBlur = SemBlurBridge.available();
    private final GlassSpec spec = new GlassSpec();
    private Boolean shadeExpanded;
    /** enabled == null: preferences unreadable, resolve through the module's ConfigProvider. */
    public HeadsUpHooks(ClassLoader loader, Boolean enabled) {
        this.loader = loader; this.enabled = Boolean.TRUE.equals(enabled); this.configPending = enabled == null;
    }
    private void resolveConfig(android.content.Context context) {
        if (!configPending) return;
        configPending = false;
        android.content.ContentResolver resolver = context.getContentResolver();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        // Cross-process query may start the module app; keep it off SystemUI's main thread.
        new Thread(() -> {
            Boolean value = null;
            try (android.database.Cursor c = resolver.query(android.net.Uri.parse("content://" + Config.AUTHORITY), null, null, null, null)) {
                if (c != null && c.moveToFirst()) value = c.getInt(0) != 0;
            } catch (RuntimeException e) { Probe.error("CONFIG_PROVIDER_FAILED", e); }
            Boolean result = value;
            main.post(() -> {
                Probe.log("CONFIG_PROVIDER", "enabled=" + result);
                if (Boolean.TRUE.equals(result)) {
                    enabled = true;
                    for (State state : new ArrayList<>(states.values())) state.invalidate();
                }
            });
        }, "oulg-config").start();
    }
    private Class<?> resolve(String name) {
        Class<?> c;
        try { c = Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError | RuntimeException e) { Probe.log("CLASS_MISSING", "name=" + name); return null; }
        // Member probing can hit unresolvable field/parameter types; that must not hide a present class.
        try { Probe.resolved(c); } catch (LinkageError | RuntimeException e) { Probe.error("CLASS_PROBE_FAILED", e); }
        return c;
    }
    private boolean hook(Class<?> type, String name, XC_MethodHook callback) {
        if (type == null) return false;
        int count = 0, hooked = 0;
        // Never climb into framework classes: hooking e.g. Dialog.dismiss would fire for every SystemUI dialog.
        ClassLoader framework = View.class.getClassLoader();
        for (Class<?> c = type; c != null && c.getClassLoader() != framework; c = c.getSuperclass()) {
            Method[] methods;
            // Firmware classes can reference types absent from this build; skip this hook, not the whole install.
            try { methods = c.getDeclaredMethods(); }
            catch (RuntimeException | LinkageError e) {
                Probe.log("METHODS_UNREADABLE", "owner=" + c.getName() + " name=" + name + " error=" + e.getClass().getSimpleName());
                return hooked > 0;
            }
            for (Method m : methods) {
                if (!m.getName().equals(name)) continue;
                count++;
                if (!installed.add(m)) continue;
                try { XposedBridge.hookMethod(m, callback); hooked++; Probe.log("HOOK", "method=" + m.toGenericString()); }
                catch (RuntimeException | LinkageError e) { Probe.error("HOOK_FAILED", e); }
            }
            if (count > 0) break;
        }
        if (count == 0) Probe.log("METHOD_MISSING", "owner=" + type.getName() + " name=" + name);
        return hooked > 0;
    }
    public void install() {
        rowClass = resolve(ROW);
        Class<?> bg = resolve(BACKGROUND);
        if (bg != null) {
            backgroundField = Reflect.field(bg, "mBackground");
            // Require the exact onDraw(Canvas) contract; unknown implementations stay native.
            try {
                Method draw = bg.getDeclaredMethod("onDraw", Canvas.class);
                if (backgroundField != null && Drawable.class.isAssignableFrom(backgroundField.getType())) {
                    XposedBridge.hookMethod(draw, new DrawHook()); drawHook = true;
                    Probe.log("DRAW_HOOK", "method=" + draw.toGenericString());
                }
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) { Probe.error("DRAW_HOOK_MISSING", e); }
            hook(bg, "setCustomBackground", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { if (p.thisObject instanceof View) observeBackground((View) p.thisObject, "BACKGROUND_UPDATE"); }
            });
        }
        XC_MethodHook rowEvent = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (rowClass != null && rowClass.isInstance(p.thisObject)) {
                        String name = ((Method) p.method).getName();
                        observeRow((View) p.thisObject, name);
                        // A new row inflates when a notification arrives; survey windows while its pop-up is on screen.
                        if ("onFinishInflate".equals(name)) WindowSurvey.schedule("rowInflate");
                    }
                }
                catch (RuntimeException | LinkageError e) { Probe.error("ROW_PROBE_FAILED", e); }
            }
        };
        for (String n : new String[]{"onFinishInflate", "setHeadsUp", "setPinned", "setHeadsUpAnimatingAway", "startAppearAnimation", "onAppearAnimationFinished", "setUserExpanded", "setOnKeyguard", "onAttachedToWindow"}) hook(rowClass, n, rowEvent);
        installShade();
        for (String manager : new String[]{"com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl", "com.android.systemui.statusbar.policy.BaseHeadsUpManager", "com.android.systemui.statusbar.policy.HeadsUpManager"}) {
            Class<?> c = resolve(manager);
            for (String n : new String[]{"showNotification", "updateNotification", "removeNotification", "createHeadsUpEntry", "setEntryPinned"}) hook(c, n, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { Probe.log("MANAGER_EVENT", "owner=" + owner(p) + " method=" + ((Method) p.method).getName()); }
            });
        }
        // Brief/Edge Lighting remains a separate discovery lane, not a notification row assumption.
        for (String name : new String[]{"com.android.systemui.edgelighting.effect.container.NotificationEffect", "com.android.systemui.edgelighting.effect.container.EdgeLightingDialog"}) {
            Class<?> c = resolve(name);
            for (String n : new String[]{"show", "dismiss", "onAttachedToWindow", "onDetachedFromWindow"}) hook(c, n, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { Probe.log("BRIEF_EVENT", "owner=" + owner(p) + " method=" + ((Method) p.method).getName()); }
            });
        }
        WindowSurvey.installWindowHook();
        // Shade state is only known once SystemUI constructs the controller, after this point.
        Probe.log("READY", "mode=" + (enabled ? "glass" : "probe") + " drawHook=" + drawHook + " shadeHook=" + shadeHook + " samsungBlur=" + samsungBlur);
        if (enabled && (!drawHook || !shadeHook)) Probe.log("GLASS_UNAVAILABLE", "drawHook=" + drawHook + " shadeHook=" + shadeHook);
    }
    private static String owner(XC_MethodHook.MethodHookParam p) {
        return p.thisObject != null ? p.thisObject.getClass().getName() : ((Method) p.method).getDeclaringClass().getName();
    }
    private void installShade() {
        Class<?> c = resolve("com.android.systemui.shade.NotificationPanelViewController");
        if (c == null) c = resolve("com.android.systemui.statusbar.phone.NotificationPanelViewController");
        if (c != null) {
            try { XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { updateShade(p.thisObject); }
            }); shadeHook = true; } catch (RuntimeException | LinkageError e) { Probe.error("SHADE_CONSTRUCTOR_FAILED", e); }
        }
        // This reads the controller's actual expanded height, rather than treating a row expansion as QS.
        if (hook(c, "setExpandedHeightInternal", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                updateShade(p.thisObject);
            }
        })) shadeHook = true;
    }
    private void updateShade(Object controller) {
        Object height = Reflect.read(controller, "mExpandedHeight");
        if (!(height instanceof Number)) height = Reflect.read(controller, "expandedHeight");
        if (!(height instanceof Number)) {
            try { height = Reflect.call(controller, "getExpandedHeight"); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        Boolean next = height instanceof Number && Float.isFinite(((Number) height).floatValue())
                ? ((Number) height).floatValue() > 0.5f : null;
        if (next != null && !next.equals(shadeExpanded)) {
            shadeExpanded = next; Probe.log("SHADE", "expanded=" + next);
            for (State state : new ArrayList<>(states.values())) { if (next) state.release(); state.invalidate(); }
        }
    }
    private void observeRow(View row, String event) {
        Object background = Reflect.read(row, "mBackgroundNormal");
        if (background instanceof View) {
            State state = state((View) background, row);
            state.event(event);
        } else Probe.log("BACKGROUND_MISSING", "row=" + row.getClass().getName());
    }
    private void observeBackground(View bg, String event) {
        View row = findRow(bg);
        if (row != null) state(bg, row).event(event);
    }
    private View findRow(View bg) {
        for (ViewParent p = bg.getParent(); p instanceof View; p = p.getParent()) {
            if (rowClass != null && rowClass.isInstance(p)) return (View) p;
        }
        return null;
    }
    private State state(View bg, View row) {
        State existing = states.get(bg);
        if (existing != null) return existing;
        State s = new State(bg, row); states.put(bg, s); bg.addOnAttachStateChangeListener(s);
        if (bg.isAttachedToWindow()) s.onViewAttachedToWindow(bg);
        return s;
    }
    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> background;
        final WeakReference<View> row;
        GlassDrawable glass;
        Backdrop.Kind glassKind;
        WindowManager wm;
        Consumer<Boolean> blurListener;
        ViewTreeObserver observer;
        boolean blurEnabled;
        boolean failed;
        boolean reportedCapability;
        Boolean lastEligible;
        String lastReason = "";
        Boolean lastHeadsUp;
        int width = -1, height = -1;
        final float[] lastRadii = new float[8];
        State(View background, View row) { this.background = new WeakReference<>(background); this.row = new WeakReference<>(row); }
        void invalidate() { View v = background.get(); if (v != null) v.invalidate(); }
        @Override public void onViewAttachedToWindow(View view) {
            if (observer != null) return;
            if (!blurEnvironmentReported) { blurEnvironmentReported = true; Probe.blurEnvironment(view.getContext()); }
            resolveConfig(view.getContext());
            observer = view.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            try {
                wm = view.getContext().getSystemService(WindowManager.class);
                if (wm != null) {
                    blurEnabled = wm.isCrossWindowBlurEnabled();
                    blurListener = supported -> {
                        blurEnabled = supported;
                        Probe.log("BLUR_CAPABILITY_CHANGED", "enabled=" + supported);
                        if (!supported && glassKind == Backdrop.Kind.COMPOSITOR) release();
                        invalidate();
                    };
                    wm.addCrossWindowBlurEnabledListener(view.getContext().getMainExecutor(), blurListener);
                }
            } catch (RuntimeException e) { Probe.error("BLUR_LISTENER_FAILED", e); }
            Probe.hierarchy(view);
            event("ATTACH");
        }
        @Override public void onViewDetachedFromWindow(View view) {
            release();
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            if (wm != null && blurListener != null) {
                try { wm.removeCrossWindowBlurEnabledListener(blurListener); }
                catch (RuntimeException e) { Probe.error("BLUR_LISTENER_REMOVE_FAILED", e); }
            }
            wm = null; blurListener = null; reportedCapability = false; lastEligible = null;
            Probe.view("DETACH", view);
        }
        @Override public boolean onPreDraw() {
            try {
                View v = background.get(); if (v == null) return true;
                boolean eligible = eligible();
                String reason = reason();
                View r = row.get();
                Boolean headsUp = r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp");
                if (headsUp != null && !headsUp.equals(lastHeadsUp)) {
                    lastHeadsUp = headsUp;
                    Probe.log("HEADSUP", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " headsUp=" + headsUp
                            + " pinned=" + Reflect.bool(r, "isPinned", "mIsPinned") + " shown=" + v.isShown()
                            + " nativeBlur=" + Reflect.bool(v, "isBlurEnabled", "mBlurEnabled"));
                }
                if (!lastReason.equals(String.valueOf(reason))) {
                    lastReason = String.valueOf(reason);
                    Probe.log("DECISION", "viewId=" + Integer.toHexString(System.identityHashCode(v))
                            + (reason == null ? " glass=true" : " glass=false reason=" + reason));
                }
                if (lastEligible == null || lastEligible != eligible) {
                    lastEligible = eligible;
                    event("ELIGIBILITY");
                    if (!eligible) release();
                    invalidate();
                }
                if (width != v.getWidth() || height != v.getHeight()) {
                    width = v.getWidth(); height = v.getHeight(); Probe.view("GEOMETRY", v);
                }
            } catch (RuntimeException | LinkageError e) { failed = true; release(); Probe.error("PREDRAW_FAILED", e); }
            return true;
        }
        boolean eligible() {
            View v = background.get(), r = row.get();
            if (v == null || r == null || failed || !drawHook || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            Boolean keyguard = Reflect.bool(r, "isOnKeyguard", "mOnKeyguard");
            return Eligibility.glass(enabled, Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"), keyguard, shadeExpanded,
                    v.isAttachedToWindow(), v.isHardwareAccelerated(), r.isPressed() || r.isFocused() || r.isHovered());
        }
        /** Diagnostic mirror of eligible() plus the compositor capability checked in material(). */
        String reason() {
            View v = background.get(), r = row.get();
            if (v == null || r == null) return "collected";
            if (failed) return "failed";
            if (!drawHook) return "drawHook=false";
            if (!v.isShown()) return "hidden";
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return "empty";
            String policy = Eligibility.reason(enabled, Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"),
                    Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"), shadeExpanded,
                    v.isAttachedToWindow(), v.isHardwareAccelerated(), r.isPressed() || r.isFocused() || r.isHovered());
            if (policy != null) return policy;
            return Backdrop.choose(blurEnabled, samsungBlur) != null ? null : "blur=unavailable";
        }
        void event(String event) {
            View v = background.get(), r = row.get(); if (v == null || r == null) return;
            Probe.view("ROW_EVENT", v);
            Probe.log("LIFECYCLE", "callback=" + event + " rowId=" + Integer.toHexString(System.identityHashCode(r))
                    + " headsUp=" + Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp") + " keyguard=" + Reflect.read(r, "mOnKeyguard")
                    + " pinned=" + Reflect.bool(r, "isPinned", "mIsPinned") + " animatingAway=" + Reflect.bool(r, "isHeadsUpAnimatingAway", "mHeadsUpAnimatingAway")
                    + " nativeBlur=" + Reflect.bool(v, "isBlurEnabled", "mBlurEnabled")
                    + " tint=" + Reflect.read(v, "mTintColor") + " radii=" + java.util.Arrays.toString(shape())
                    + " shadeExpanded=" + shadeExpanded + " crossBlur=" + blurEnabled);
            if (v.isAttachedToWindow() && !reportedCapability) {
                reportedCapability = true;
                try {
                    BackgroundBlurBridge bridge = BackgroundBlurBridge.create(v);
                    Probe.log("BLUR_FACTORY", "available=true drawable=" + bridge.drawable.getClass().getName()); bridge.release();
                } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("BLUR_FACTORY_FAILED", e); }
            }
        }
        float[] shape() {
            View v = background.get(); Object shape = Reflect.read(v, "mCornerRadii");
            if (shape instanceof float[] && ((float[]) shape).length == 8) {
                float[] radii = (float[]) shape;
                for (int i = 0; i < 8; i++) lastRadii[i] = Float.isFinite(radii[i]) ? Math.max(0, radii[i]) : 0;
            }
            return lastRadii;
        }
        GlassDrawable material(Drawable original) throws ReflectiveOperationException {
            View v = background.get();
            Backdrop.Kind kind = Backdrop.choose(blurEnabled, samsungBlur);
            if (v == null || !eligible() || kind == null) { release(); return null; }
            for (int state : original.getState()) {
                if (state == android.R.attr.state_pressed || state == android.R.attr.state_focused || state == android.R.attr.state_hovered) {
                    release(); return null;
                }
            }
            if (glass != null && glass.failed()) { failed = true; release(); return null; }
            if (glass != null && glassKind != kind) release();
            if (!CornerGeometry.supported(Reflect.read(v, "mCornerRadii"))) {
                failed = true; Probe.log("GEOMETRY_UNSUPPORTED", "view=" + v.getClass().getName()); return null;
            }
            if (kind == Backdrop.Kind.SAMSUNG && !CornerGeometry.uniform(Reflect.read(v, "mCornerRadii"))) {
                // Samsung blur takes one corner radius; mixed top/bottom corners stay native this frame.
                release(); return null;
            }
            if (glass == null) {
                Backdrop backdrop = kind == Backdrop.Kind.SAMSUNG ? SemBlurBridge.create(v) : BackgroundBlurBridge.create(v);
                glass = new GlassDrawable(backdrop, v.getResources().getDisplayMetrics().density, spec);
                glassKind = kind;
                glass.setCallback(v);
                Probe.log("GLASS_APPLIED", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " source=" + backdrop.name() + " optics=edge_shader");
            }
            boolean dark = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            glass.configure(original, shape(), dark ? spec.darkTint : spec.lightTint);
            return glass;
        }
        void release() {
            if (glass != null) { glass.release(); glass = null; glassKind = null; Probe.log("GLASS_RELEASED", "native=true"); }
        }
    }
    private final class DrawHook extends XC_MethodHook {
        private boolean reported;
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (!(p.thisObject instanceof View) || backgroundField == null) return;
            View v = (View) p.thisObject;
            if (!reported) { reported = true; Probe.log("DRAW_HOOK_CALLED", "view=" + v.getClass().getName()); }
            try {
                View row = findRow(v); if (row == null) return;
                State s = state(v, row);
                Object current = backgroundField.get(v); if (!(current instanceof Drawable)) return;
                GlassDrawable glass = s.material((Drawable) current); if (glass == null) return;
                // Restore even when the native draw throws; no permanent drawable replacement.
                p.setObjectExtra("oulgOriginal", current);
                p.setObjectExtra("oulgSaveCount", ((Canvas) p.args[0]).getSaveCount());
                backgroundField.set(v, glass);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                State s = states.get(v); if (s != null) { s.failed = true; s.release(); }
                Probe.error("DRAW_SWAP_FAILED", e);
            }
        }
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object original = p.getObjectExtra("oulgOriginal"); if (original == null) return;
            try { backgroundField.set(p.thisObject, original); }
            catch (IllegalAccessException | RuntimeException e) { Probe.error("DRAW_RESTORE_FAILED", e); }
            if (p.hasThrowable()) {
                State s = states.get((View) p.thisObject); if (s != null) { s.failed = true; s.release(); }
                Probe.error("NATIVE_DRAW_FAILED", p.getThrowable());
                try {
                    Object count = p.getObjectExtra("oulgSaveCount");
                    if (count instanceof Integer) ((Canvas) p.args[0]).restoreToCount((Integer) count);
                    Object result = XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args);
                    p.setResult(result);
                    Probe.log("NATIVE_DRAW_RECOVERED", "glassDisabled=true");
                } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NATIVE_DRAW_RECOVERY_FAILED", e); }
            }
        }
    }
}

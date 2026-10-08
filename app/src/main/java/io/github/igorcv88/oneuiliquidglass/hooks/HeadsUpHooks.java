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
import io.github.igorcv88.oneuiliquidglass.glass.CaptureBackdrop;
import io.github.igorcv88.oneuiliquidglass.glass.CaptureHub;
import io.github.igorcv88.oneuiliquidglass.glass.GridBackdrop;
import io.github.igorcv88.oneuiliquidglass.glass.HybridBackdrop;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import io.github.igorcv88.oneuiliquidglass.glass.WallpaperBackdrop;
import io.github.igorcv88.oneuiliquidglass.glass.SemBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.SharedBackdrop;
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
    private Field clipTopField, clipBottomField, actualHeightField, expandRunningField;
    private boolean drawHook;
    private boolean shadeHook;
    private boolean blurEnvironmentReported;
    private final boolean samsungBlur = SemBlurBridge.available();
    private final GlassSpec spec = new GlassSpec();
    private Boolean shadeExpanded;
    /** Set once HybridBackdrop could not be built; heads-up rows then keep the plain compositor blur. */
    private boolean hybridFailed;
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
            clipTopField = Reflect.field(bg, "mClipTopAmount");
            clipBottomField = Reflect.field(bg, "mClipBottomAmount");
            actualHeightField = Reflect.field(bg, "mActualHeight");
            expandRunningField = Reflect.field(bg, "mExpandAnimationRunning");
            Probe.log("CLIP_FIELDS", "clipTop=" + (clipTopField != null) + " clipBottom=" + (clipBottomField != null)
                    + " actualHeight=" + (actualHeightField != null) + " expandRunning=" + (expandRunningField != null));
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
        installBlurGuard();
        // Shade state is only known once SystemUI constructs the controller, after this point.
        Probe.log("READY", "mode=" + (enabled ? "glass" : "probe") + " drawHook=" + drawHook + " shadeHook=" + shadeHook + " samsungBlur=" + samsungBlur);
        if (enabled && (!drawHook || !shadeHook)) Probe.log("GLASS_UNAVAILABLE", "drawHook=" + drawHook + " shadeHook=" + shadeHook);
    }
    private final Set<String> foreignBlurLogged = new java.util.HashSet<>();
    /** Stack walks are costly on a per-frame path; provenance is only gathered for the first calls. */
    private int foreignBlurTraces = 200;
    /**
     * Another component (a Theme Park theme on One UI) can set its own Samsung blur on the same
     * notification views and replace ours: on device that left the blur in a band in the middle of
     * the card. Calls not made by SemBlurBridge are logged once per view class and caller, and
     * blocked on views whose material this module manages with the Samsung blur.
     */
    private void installBlurGuard() {
        try {
            XposedBridge.hookAllMethods(View.class, "semSetBlurInfo", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (SemBlurBridge.applying() || !(p.thisObject instanceof View)) return;
                    try {
                        View v = (View) p.thisObject;
                        // Remember SystemUI's own blur on notification views so a released material
                        // hands it back instead of clearing it.
                        if (states.containsKey(v) || (rowClass != null && rowClass.isInstance(v))
                                || BACKGROUND.equals(v.getClass().getName())) SemBlurBridge.recordNative(v, p.args[0]);
                        boolean managed = compositorState(v) != null;
                        String caller = foreignBlurTraces > 0 && foreignBlurLogged.size() < 40 ? blurCaller() : null;
                        if (caller != null) foreignBlurTraces--;
                        if (caller != null && foreignBlurLogged.add(v.getClass().getName() + "|" + caller + "|" + managed)) {
                            Probe.log("SEM_BLUR_FOREIGN", "view=" + v.getClass().getName() + " id=" + Integer.toHexString(System.identityHashCode(v))
                                    + " managed=" + managed + " blocked=" + managed + " caller=" + caller + " info=" + (p.args[0] == null ? "null" : "set"));
                        }
                        if (managed) p.setResult(null);
                    } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_GUARD_FAILED", e); }
                }
            });
            Probe.log("BLUR_GUARD", "installed=true");
        } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_GUARD_INSTALL_FAILED", e); }
        installBlurMutators();
    }
    /** The row state whose material drives this view's Samsung blur (Samsung or hybrid), if any. */
    private State compositorState(View v) {
        State s = states.get(v);
        if (s == null && rowClass != null && rowClass.isInstance(v)) {
            Object bg = Reflect.read(v, "mBackgroundNormal");
            s = bg instanceof View ? states.get(bg) : null;
        }
        return s != null && s.glass != null && (s.glassKind == Backdrop.Kind.SAMSUNG || s.glass.hybrid()) ? s : null;
    }
    private final Set<String> mutatorLogged = new java.util.HashSet<>();
    /**
     * Event-driven repair instead of polling: every other View method that changes blur state
     * (whatever this firmware names them) is hooked once. They cost nothing until called; when one
     * touches a card whose blur this module drives, that card's blur is applied again once.
     */
    private void installBlurMutators() {
        java.util.List<String> names = new ArrayList<>();
        try {
            for (Method m : View.class.getDeclaredMethods()) {
                String n = m.getName();
                String lower = n.toLowerCase(java.util.Locale.ROOT);
                // Setter-like names only: a per-frame draw/update path would turn repair into polling.
                if (m.getReturnType() != void.class || n.equals("semSetBlurInfo") || !lower.contains("blur")
                        || !lower.matches("^(sem)?(set|clear|reset|remove|enable|disable).*")
                        || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (SemBlurBridge.applying() || !(p.thisObject instanceof View)) return;
                            try {
                                State s = compositorState((View) p.thisObject);
                                if (s == null) return;
                                if (mutatorLogged.size() < 40 && mutatorLogged.add(n + "|" + p.thisObject.getClass().getName())) {
                                    Probe.log("SEM_BLUR_MUTATED", "method=" + n + " view=" + p.thisObject.getClass().getName()
                                            + " caller=" + caller(n));
                                }
                                s.glass.reassertBackdrop();
                            } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_MUTATOR_FAILED", e); }
                        }
                    });
                    names.add(n);
                } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_MUTATOR_HOOK_FAILED", e); }
            }
        } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_MUTATORS_UNREADABLE", e); }
        Probe.log("BLUR_MUTATORS", "hooked=" + names);
    }
    /**
     * The code that called a hooked View method: the frames after its last frame (the hooked
     * method or its LSPosed stub). Matching hook classes by name failed: LSPosed obfuscates them.
     */
    private static String blurCaller() { return caller("semSetBlurInfo"); }
    private static String caller(String method) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int last = -1;
        for (int i = 0; i < stack.length; i++) if (method.equals(stack[i].getMethodName())) last = i;
        if (last < 0 || last + 1 >= stack.length) return "unknown";
        StringBuilder out = new StringBuilder();
        for (int i = last + 1; i < Math.min(stack.length, last + 4); i++) {
            if (out.length() > 0) out.append('<');
            out.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
        }
        return out.toString();
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
            for (State state : new ArrayList<>(states.values())) {
                // Opening the shade is when cards were seen without their blur: apply it again.
                if (state.glass != null) state.glass.reassertBackdrop();
                state.invalidate();
            }
            // The panel controller's own view works with an empty shade; a row is only the fallback.
            Object panel = Reflect.read(controller, "mView");
            View sample = panel instanceof View && ((View) panel).isAttachedToWindow() ? (View) panel : null;
            for (State state : new ArrayList<>(states.values())) {
                if (sample != null) break;
                View v = state.background.get(); if (v != null && v.isAttachedToWindow()) sample = v;
            }
            if (sample != null) Probe.scrims(sample.getRootView(), next ? "shadeExpanded" : "shadeCollapsed");
            else Probe.log("SHADE_PROBE_SKIPPED", "reason=noAttachedView");
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
        String glassSource;
        boolean materialReported;
        android.graphics.RenderNode scratch;
        WindowManager wm;
        Consumer<Boolean> blurListener;
        ViewTreeObserver observer;
        boolean blurEnabled;
        boolean failed;
        boolean reportedCapability;
        Boolean lastEligible;
        String lastReason = "";
        /** Last reason the native background was drawn instead of the glass; null while glass draws. */
        String lastFallback;
        Boolean lastHeadsUp;
        int width = -1, height = -1;
        final float[] lastRadii = new float[8];
        final int[] location = new int[2], lastLocation = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        int tuningGeneration;
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
                View r = row.get();
                Boolean headsUp = r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp");
                String reason = reason();
                if (headsUp != null && !headsUp.equals(lastHeadsUp)) {
                    lastHeadsUp = headsUp;
                    Probe.log("HEADSUP", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " headsUp=" + headsUp
                            + " pinned=" + Reflect.bool(r, "isPinned", "mIsPinned") + " shown=" + v.isShown()
                            + " nativeBlur=" + Reflect.bool(v, "isBlurEnabled", "mBlurEnabled"));
                }
                String surface = Eligibility.surface(headsUp, r == null ? null : Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"), shadeExpanded);
                String decision = surface + "|" + reason;
                if (!lastReason.equals(decision)) {
                    lastReason = decision;
                    Probe.log("DECISION", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " surface=" + surface
                            + (reason == null ? " glass=true backdrop=" + kind() : " glass=false reason=" + reason));
                }
                if (tuningGeneration != Tuning.generation) {
                    // A debug.oulg.* knob changed: redraw so the material (and Samsung blur) rebuilds now.
                    tuningGeneration = Tuning.generation; v.invalidate();
                }
                if (glassKind == Backdrop.Kind.SAMPLED) {
                    // Slide and stack animations move the row through RenderNode properties without
                    // re-recording; the sampled backdrop must follow the screen position every frame.
                    v.getLocationOnScreen(location);
                    if (location[0] != lastLocation[0] || location[1] != lastLocation[1]) {
                        lastLocation[0] = location[0]; lastLocation[1] = location[1]; v.invalidate();
                    }
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
            return Eligibility.glass(enabled, v.isAttachedToWindow(), v.isHardwareAccelerated(), r.isPressed() || r.isFocused() || r.isHovered());
        }
        /** Diagnostic mirror of eligible() plus the compositor capability checked in material(). */
        String reason() {
            View v = background.get(), r = row.get();
            if (v == null || r == null) return "collected";
            if (failed) return "failed";
            if (!drawHook) return "drawHook=false";
            if (!v.isShown()) return "hidden";
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return "empty";
            String policy = Eligibility.reason(enabled, v.isAttachedToWindow(), v.isHardwareAccelerated(), r.isPressed() || r.isFocused() || r.isHovered());
            if (policy != null) return policy;
            return kind() != null ? null : "blur=unavailable";
        }
        Backdrop.Kind kind() { return Backdrop.choose(blurEnabled, samsungBlur, sharedBackdrop(), sampledRow()); }
        /**
         * Rows whose background can be sampled and refracted. By default only lockscreen rows: the
         * wallpaper is static, so it is redrawn on the GPU every frame with no lag. The app behind a
         * heads-up lives in another process and can only be captured periodically, which visibly
         * lags behind motion, so it keeps the live compositor blur unless debug.oulg.backdrop=capture.
         */
        boolean sampledRow() {
            View r = row.get(), v = background.get();
            if (r == null || v == null || sharedBackdrop()) return false;
            String mode = Tuning.get().backdrop;
            if (mode.equals("off") || !GlassDrawable.refractionAvailable()) return false;
            Boolean keyguard = Reflect.bool(r, "isOnKeyguard", "mOnKeyguard");
            if (!Eligibility.captureSurface(Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"), keyguard, shadeExpanded)) return false;
            switch (mode) {
                case "grid": return true;
                case "capture": return CaptureHub.available();
                default:
                    // Lockscreen: the still wallpaper, else a slow capture (the keyguard barely moves).
                    if (Boolean.TRUE.equals(keyguard)) return WallpaperBackdrop.available(v.getContext()) || CaptureHub.available();
                    // Heads-up: live compositor body plus a captured lens band; needs both halves.
                    return samsungBlur && !hybridFailed && CaptureHub.available() && SemBlurBridge.supports(Reflect.read(v, "mCornerRadii"));
            }
        }
        boolean onKeyguard() { View r = row.get(); return r != null && Boolean.TRUE.equals(Reflect.bool(r, "isOnKeyguard", "mOnKeyguard")); }
        /** Which sampled source this row wants now; a change replaces the material. */
        String sampledSource(View v) {
            String mode = Tuning.get().backdrop;
            if (mode.equals("grid")) return "grid";
            if (mode.equals("capture")) return onKeyguard() ? "capture-keyguard" : "capture";
            if (onKeyguard()) return WallpaperBackdrop.available(v.getContext()) ? "wallpaper" : "capture-keyguard";
            return "hybrid";
        }
        Backdrop sampledBackdrop(View v, String source) throws ReflectiveOperationException {
            switch (source) {
                case "grid": return new GridBackdrop(v);
                case "wallpaper": return new WallpaperBackdrop(v);
                case "capture-keyguard": return new CaptureBackdrop(v, true);
                case "hybrid":
                    try { return new HybridBackdrop(v); }
                    catch (ReflectiveOperationException | RuntimeException e) {
                        // Never fall back to a full-surface capture here: that is the lagging body
                        // the hybrid exists to avoid. Plain compositor blur, and no hybrid again.
                        hybridFailed = true;
                        Probe.error("HYBRID_FAILED", e);
                        return SemBlurBridge.create(v);
                    }
                default: return new CaptureBackdrop(v, false);
            }
        }
        void event(String event) {
            View v = background.get(), r = row.get(); if (v == null || r == null) return;
            if (glass != null) glass.reassertBackdrop();
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
            Backdrop.Kind kind = kind();
            if (v == null || !eligible() || kind == null) { release(); return fallback(v == null ? "collected" : kind == null ? "noBackdrop" : reason()); }
            for (int state : original.getState()) {
                if (state == android.R.attr.state_pressed || state == android.R.attr.state_focused || state == android.R.attr.state_hovered) {
                    release(); return fallback("pressed");
                }
            }
            if (glass != null && glass.failed()) { failed = true; release(); return fallback("glassFailed"); }
            String source = kind == Backdrop.Kind.SAMPLED ? sampledSource(v) : null;
            if (glass != null && (glassKind != kind || glass.stale() || !java.util.Objects.equals(source, glassSource))) release();
            if (!CornerGeometry.supported(Reflect.read(v, "mCornerRadii"))) {
                // Not sticky: a shape seen mid-animation must not leave the row native for its lifetime.
                release(); return fallback("geometry");
            }
            if (kind == Backdrop.Kind.SAMSUNG && !SemBlurBridge.supports(Reflect.read(v, "mCornerRadii"))) {
                // Corner order is only known for top/bottom-symmetric shapes; others stay native this frame.
                release(); return fallback("corners");
            }
            if (glass == null) {
                Backdrop backdrop = kind == Backdrop.Kind.SHARED ? new SharedBackdrop()
                        : kind == Backdrop.Kind.SAMPLED ? sampledBackdrop(v, source)
                        : kind == Backdrop.Kind.SAMSUNG ? SemBlurBridge.create(v) : BackgroundBlurBridge.create(v);
                glass = new GlassDrawable(backdrop, v.getResources().getDisplayMetrics().density, spec);
                glassKind = kind;
                glassSource = source;
                glass.setCallback(v);
                Probe.log("GLASS_APPLIED", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " source=" + backdrop.name()
                        + " optics=" + (kind == Backdrop.Kind.SAMPLED ? "refraction" : "edge_shader"));
                materialReported = false;
                Probe.painters(row.get());
            }
            boolean dark = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fill = kind == Backdrop.Kind.SHARED ? (dark ? spec.shadeDarkFill : spec.shadeLightFill) : (dark ? spec.darkFill : spec.lightFill);
            int tone = glass.hybrid() || kind == Backdrop.Kind.SAMSUNG ? (dark ? spec.samsungDarkColor : spec.samsungLightColor)
                    : kind == Backdrop.Kind.SAMPLED ? (dark ? spec.captureDarkTint : spec.captureLightTint)
                    : dark ? spec.darkBlurColor : spec.lightBlurColor;
            glass.configure(original, shape(), fill, tone);
            if (lastFallback != null) { lastFallback = null; Probe.log("NATIVE_FALLBACK", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " reason=none"); }
            if (!materialReported) {
                materialReported = true;
                Probe.material(v, row.get(), original, String.valueOf(kind), fill, tone);
            }
            return glass;
        }
        /** The native background draws this frame; logged when the reason changes. */
        GlassDrawable fallback(String why) {
            why = String.valueOf(why);
            if (!why.equals(lastFallback)) {
                lastFallback = why;
                View v = background.get(), r = row.get();
                Probe.log("NATIVE_FALLBACK", "viewId=" + (v == null ? "null" : Integer.toHexString(System.identityHashCode(v))) + " reason=" + why
                        + " headsUp=" + (r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp")) + " shadeExpanded=" + shadeExpanded
                        + " radii=" + java.util.Arrays.toString(shape()));
            }
            return null;
        }
        /** See {@link Eligibility#sharedBackdrop}. */
        boolean sharedBackdrop() {
            View r = row.get();
            if (r == null || Tuning.get().shadeBlur) return false;
            return Eligibility.sharedBackdrop(shadeExpanded, Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"));
        }
        void release() {
            if (glass == null) return;
            // The blur guard also blocked calls aimed at the row while this material was managed.
            boolean compositor = glassKind == Backdrop.Kind.SAMSUNG || glass.hybrid();
            glass.release(); glass = null; glassKind = null; glassSource = null; Probe.log("GLASS_RELEASED", "native=true");
            View r = row.get();
            if (compositor && r != null) SemBlurBridge.restoreNative(r);
        }
    }
    /**
     * The native onDraw records into a throwaway RenderNode so it still computes the drawable's
     * bounds; the glass is then drawn on the real canvas with those bounds and the native clip.
     * The private background field is never written: on One UI 9 it is typed SeslRecoilDrawable.
     */
    private final class DrawHook extends XC_MethodHook {
        private boolean reported;
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            if (!(p.thisObject instanceof View) || backgroundField == null || !(p.args[0] instanceof Canvas)) return;
            View v = (View) p.thisObject;
            if (!reported) { reported = true; Probe.log("DRAW_HOOK_CALLED", "view=" + v.getClass().getName()); }
            try {
                View row = findRow(v); if (row == null) return;
                State s = state(v, row);
                Object current = backgroundField.get(v); if (!(current instanceof Drawable)) return;
                GlassDrawable glass = s.material((Drawable) current); if (glass == null) return;
                if (s.scratch == null) s.scratch = new android.graphics.RenderNode("oulg-native-bounds");
                Canvas scratch = s.scratch.beginRecording(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()));
                p.setObjectExtra("oulgCanvas", p.args[0]);
                p.setObjectExtra("oulgGlass", glass);
                p.args[0] = scratch;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                State s = states.get(v); if (s != null) { s.failed = true; s.release(); }
                Probe.error("DRAW_SWAP_FAILED", e);
            }
        }
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object real = p.getObjectExtra("oulgCanvas"); if (!(real instanceof Canvas)) return;
            View v = (View) p.thisObject;
            State s = states.get(v);
            if (s != null && s.scratch != null) { s.scratch.endRecording(); s.scratch.discardDisplayList(); }
            p.args[0] = real;
            Canvas canvas = (Canvas) real;
            if (p.hasThrowable()) {
                if (s != null) { s.failed = true; s.release(); }
                Probe.error("NATIVE_DRAW_FAILED", p.getThrowable());
                try {
                    p.setResult(XposedBridge.invokeOriginalMethod(p.method, p.thisObject, p.args));
                    Probe.log("NATIVE_DRAW_RECOVERED", "glassDisabled=true");
                } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NATIVE_DRAW_RECOVERY_FAILED", e); }
                return;
            }
            try {
                Drawable current = (Drawable) backgroundField.get(v);
                GlassDrawable glass = (GlassDrawable) p.getObjectExtra("oulgGlass");
                android.graphics.Rect bounds = current.getBounds();
                if (bounds.isEmpty()) return;
                int save = canvas.save();
                try {
                    clipLikeNative(v, canvas);
                    glass.setBounds(bounds);
                    glass.draw(canvas);
                } finally { canvas.restoreToCount(save); }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (s != null) { s.failed = true; s.release(); }
                Probe.error("GLASS_COMPOSE_FAILED", e);
                // The real canvas received nothing this frame; redraw natively.
                v.invalidate();
            }
        }
    }
    /** AOSP NotificationBackgroundView clips to [clipTop, actualHeight - clipBottom] unless expanding. */
    private void clipLikeNative(View v, Canvas canvas) throws IllegalAccessException {
        if (clipTopField == null || clipBottomField == null || actualHeightField == null) return;
        if (expandRunningField != null && expandRunningField.getBoolean(v)) return;
        int top = clipTopField.getInt(v), bottom = actualHeightField.getInt(v) - clipBottomField.getInt(v);
        canvas.clipRect(0, top, v.getWidth(), Math.max(top, bottom));
    }
}

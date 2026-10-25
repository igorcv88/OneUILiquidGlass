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
import io.github.igorcv88.oneuiliquidglass.diagnostics.Perf;
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
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;
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
    /** Counts shade openings; a card measures its veil once per opening (shadetone=3). */
    private int shadeOpens;
    /** SystemUI's StatusBarState (0 shade, 1 keyguard, 2 shade over keyguard); null until first set. */
    private Integer barState;
    /** When barState last changed (uptime ms), for the optics timeline. */
    private long barStateAt;
    /** Set once HybridBackdrop could not be built; heads-up rows then keep the plain compositor blur. */
    private boolean hybridFailed;
    /**
     * Set once ViewRootImpl's blur drawable factory or one of its setters is missing on this firmware:
     * cards then take the Samsung blur for the rest of the process instead of the drawable path.
     */
    private boolean drawableUnavailable;
    /** Last cross-window blur capability logged (BLUR_CAPABILITY_CHANGED), across all cards. */
    private Boolean lastCapability;
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
        new HeaderGapHooks(loader, rowClass, () -> enabled).install();
        installShade();
        installBarState();
        installScrimClamp();
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
    private String lastPanelBlur;
    private final Set<String> foreignBlurLogged = new java.util.HashSet<>();
    private final Set<String> shelfTagLogged = new java.util.HashSet<>();
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
                    if (!(p.thisObject instanceof View)) return;
                    long started = Perf.start();
                    try { guard(p); } finally { Perf.end(Perf.BLUR_GUARD, started); }
                }
                private void guard(MethodHookParam p) {
                    if (SemBlurBridge.applying()) {
                        // A clear nested inside our own apply, on the view we are applying to.
                        if (p.args[0] == null && p.thisObject == SemBlurBridge.applyingHost()) {
                            if (nestedClears++ < 5) Probe.log("SEM_BLUR_NESTED_CLEAR", "view=" + p.thisObject.getClass().getName()
                                    + " blocked=true caller=" + nestedCaller());
                            p.setResult(null);
                        }
                        return;
                    }
                    try {
                        View v = (View) p.thisObject;
                        // Remember SystemUI's own blur on notification views so a released material
                        // hands it back instead of clearing it.
                        if (states.containsKey(v) || (rowClass != null && rowClass.isInstance(v))
                                || BACKGROUND.equals(v.getClass().getName())) SemBlurBridge.recordNative(v, p.args[0]);
                        if (v.getRootView() == v && p.args[0] != null && barState != null && barState == Eligibility.BAR_KEYGUARD
                                && (Tuning.get().sfRefract || (enabled && Tuning.get().live)) && !Tuning.get().kgWinBlur && v.getClass().getName().endsWith("NotificationShadeWindowView")
                                && Reflect.read(p.args[0], "mBlurRadius") instanceof Integer && (Integer) Reflect.read(p.args[0], "mBlurRadius") <= 4) {
                            // On the idle lockscreen a touch makes the panel blur ramp the shade window to a
                            // radius of 1-3 and back, each step with the panel's dark color curve. Only radii
                            // up to 4 are dropped: a pull-down or the bouncer passes that within a few frames.
                            p.args[0] = null;
                        }
                        if (Probe.trace && v.getRootView() == v) {
                            // The shade window's own blur, to match a card region to it before removal.
                            String panel = p.args[0] == null ? "null" : Probe.blurFields(p.args[0]);
                            if (!panel.equals(lastPanelBlur)) { lastPanelBlur = panel; Probe.log("PANEL_BLUR", "view=" + v.getClass().getSimpleName() + " " + panel); }
                        }
                        if (p.args[0] != null && v.getClass().getName().endsWith("NotificationShelfBackgroundView")) {
                            // The shelf under a collapsed stack keeps SystemUI's own blur, which a white
                            // app behind turned white: it gets the shade legibility tag too.
                            String done = SemBlurBridge.tagForeign(p.args[0]);
                            if (shelfTagLogged.add(done.replaceAll("[0-9.]+", "#"))) Probe.log("SEM_BLUR_SHELF", "tag=" + done);
                            traceShelf(v);
                        }
                        boolean managed = compositorState(v) != null;
                        String caller = Probe.trace && foreignBlurTraces > 0 && foreignBlurLogged.size() < 40 ? blurCaller() : null;
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
    private final Set<View> tracedShelves = Collections.newSetFromMap(new WeakHashMap<>());
    /** Per-frame state of the shelf's own blur, as ROW_FRAME does for rows (debug.oulg.trace only). */
    private void traceShelf(View shelf) {
        if (!tracedShelves.add(shelf)) return;
        // Registered while attached only: the window's observer outlives a shelf that is replaced.
        shelf.addOnAttachStateChangeListener(new ShelfTrace(shelf));
    }
    private static final class ShelfTrace implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final View shelf;
        private ViewTreeObserver observer;
        private String last;
        ShelfTrace(View shelf) { this.shelf = shelf; if (shelf.isAttachedToWindow()) onViewAttachedToWindow(shelf); }
        @Override public void onViewAttachedToWindow(View v) {
            if (observer != null) return;
            observer = v.getViewTreeObserver(); observer.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        @Override public boolean onPreDraw() {
            if (!Probe.trace) return true;
            try {
                String line = FrameTrace.describe(shelf, null, null);
                if (!line.equals(last)) { last = line; FrameTrace.log("SHELF_FRAME", shelf, line); }
            } catch (RuntimeException | LinkageError e) { Probe.error("SHELF_TRACE_FAILED", e); }
            return true;
        }
    }
    /** The row state whose material drives this view's Samsung blur (Samsung or hybrid), if any. */
    /** A row inside a group's children container (drawn as a stacked sliver while the group is collapsed). */
    private static boolean groupChild(View row) {
        ViewParent p = row.getParent();
        return p != null && p.getClass().getName().endsWith("NotificationChildrenContainer");
    }
    private State compositorState(View v) {
        State s = states.get(v);
        if (s == null && rowClass != null && rowClass.isInstance(v)) {
            Object bg = Reflect.read(v, "mBackgroundNormal");
            s = bg instanceof View ? states.get(bg) : null;
        }
        return s != null && s.glass != null && (s.glassKind == Backdrop.Kind.SAMSUNG || s.glass.hybrid() || s.compBridge != null) ? s : null;
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
                            long started = Perf.start();
                            try {
                                State s = compositorState((View) p.thisObject);
                                if (s == null) return;
                                if (mutatorLogged.size() < 40 && mutatorLogged.add(n + "|" + p.thisObject.getClass().getName())) {
                                    Probe.log("SEM_BLUR_MUTATED", "method=" + n + " view=" + p.thisObject.getClass().getName()
                                            + " caller=" + (Probe.trace ? caller(n) : "-"));
                                }
                                s.glass.reassertBackdrop();
                            } catch (RuntimeException | LinkageError e) { Probe.error("BLUR_MUTATOR_FAILED", e); }
                            finally { Perf.end(Perf.BLUR_MUTATOR, started); }
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
    private int nestedClears;
    /** Frames below the innermost semSetBlurInfo: who issued a clear nested inside our apply. */
    private static String nestedCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        int first = -1;
        for (int i = 0; i < stack.length; i++) if ("semSetBlurInfo".equals(stack[i].getMethodName())) { first = i; break; }
        if (first < 0) return "unknown";
        StringBuilder out = new StringBuilder();
        for (int i = first + 1; i < Math.min(stack.length, first + 9); i++) {
            if (out.length() > 0) out.append('<');
            out.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
        }
        return out.toString();
    }
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
    /** Resource entry name of a view's id, or "" (scrims are told apart by their ids). */
    private static String idName(View v) {
        try { return v.getId() == View.NO_ID ? "" : v.getResources().getResourceEntryName(v.getId()); }
        catch (RuntimeException e) { return ""; }
    }
    private static boolean notificationsScrim(View v) { return idName(v).contains("notification"); }
    private final java.util.Set<View> notificationScrims = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * A partial pull-down on the lockscreen raises the notifications scrim (the tinted layer
     * SystemUI draws behind the stack) to full opacity, and it stays there after the shade springs
     * back until the next tap. Behind opaque native cards it never shows; behind glass it turned
     * lockscreen cards dark. On the keyguard state it is held at 0.
     */
    private void installScrimClamp() {
        Class<?> c = resolve("com.android.systemui.scrim.ScrimView");
        hook(c, "setViewAlpha", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof View) || !(p.args.length > 0 && p.args[0] instanceof Float)) return;
                View v = (View) p.thisObject;
                if (!notificationsScrim(v)) return;
                if (notificationScrims.add(v)) Probe.log("SCRIM_NOTIFICATIONS", "id=" + idName(v) + " view=" + Integer.toHexString(System.identityHashCode(v)));
                if (barState != null && barState == Eligibility.BAR_KEYGUARD && (Float) p.args[0] > 0f) p.args[0] = 0f;
            }
        });
    }
    private void clampNotificationScrims() {
        for (View v : new ArrayList<>(notificationScrims)) {
            try { v.getClass().getMethod("setViewAlpha", float.class).invoke(v, 0f); }
            catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SCRIM_CLAMP_FAILED", e); }
        }
    }
    private void installBarState() {
        Class<?> c = resolve("com.android.systemui.statusbar.StatusBarStateControllerImpl");
        hook(c, "setState", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object state = Reflect.read(p.thisObject, "mState");
                if (!(state instanceof Integer) || state.equals(barState)) return;
                barState = (Integer) state;
                barStateAt = android.os.SystemClock.uptimeMillis();
                Probe.log("BAR_STATE", "state=" + barState);
                // Every card's optics timeline starts over on a state change (OPTICS lines).
                for (State s : states.values()) { s.opticsLines = 0; s.finalSeen = false; s.lastOptics = null; }
                if (barState == Eligibility.BAR_KEYGUARD) clampNotificationScrims();
                if (Probe.trace && barState == Eligibility.BAR_KEYGUARD) {
                    // The overflow pill: every shelf, icon container or footer in the window once settled.
                    for (State s : states.values()) {
                        View v = s.background.get();
                        if (v == null || !v.isAttachedToWindow()) continue;
                        View root = v.getRootView();
                        v.postDelayed(() -> Probe.overflow(root), 1200);
                        break;
                    }
                }
                // The blur is a view property: a card SystemUI does not redraw (the shade springing
                // back to the lockscreen) would keep the expanded-shade blur. Re-decide now.
                for (State s : new ArrayList<>(states.values())) { s.refreshLens(); s.invalidate(); }
                if (Probe.trace) {
                    // Scrims settle after the state change; snapshot them once the animation is done.
                    int at = barState;
                    for (State s : states.values()) {
                        View v = s.background.get();
                        if (v == null || !v.isAttachedToWindow()) continue;
                        View root = v.getRootView();
                        v.postDelayed(() -> Probe.scrims(root, "barState=" + at), 900);
                        break;
                    }
                }
            }
        });
    }
    private void updateShade(Object controller) {
        long started = Perf.start();
        try { applyShade(controller); } finally { Perf.end(Perf.SHADE, started); }
    }
    private void applyShade(Object controller) {
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
            if (next) shadeOpens++;
            if (Probe.trace && next) {
                // Every blur in the shade window once it has settled: finds blurs no hook knows about.
                for (State s : states.values()) {
                    View v = s.background.get();
                    if (v == null || !v.isAttachedToWindow()) continue;
                    View root = v.getRootView();
                    v.postDelayed(() -> Probe.blurCensus(root, "shadeOpen"), 1200);
                    break;
                }
            }
            for (State state : new ArrayList<>(states.values())) {
                // Opening the shade is when cards were seen without their blur: apply it again.
                if (state.glass != null) state.glass.reassertBackdrop();
                state.refreshLens();
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
    private static boolean shelf(View v) { return v.getClass().getName().endsWith("NotificationShelf"); }
    private final Set<String> hostless = new java.util.HashSet<>();
    /**
     * The view whose material a notification background draws: its row, or the notification shelf
     * (on the lockscreen, the pill with the icons of notifications that do not fit). A background
     * under neither is logged once per class and chain and stays native.
     */
    private View findHost(View bg) {
        View row = findRow(bg);
        if (row != null) return row;
        StringBuilder chain = new StringBuilder();
        for (ViewParent p = bg.getParent(); p instanceof View; p = p.getParent()) {
            if (shelf((View) p)) return (View) p;
            if (chain.length() < 300) chain.append('<').append(p.getClass().getSimpleName());
        }
        if (hostless.size() < 20 && hostless.add(bg.getClass().getName() + chain)) {
            Probe.log("BACKGROUND_HOSTLESS", "view=" + bg.getClass().getName() + " chain=" + chain);
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
    /** How long a card stays untagged after it last scaled or faded. */
    private static final long SETTLE_MS = 300;
    /** How long a card's shade veil takes to move to a new measurement. */
    private static final long VEIL_RAMP_MS = 180;
    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> background;
        final WeakReference<View> row;
        GlassDrawable glass;
        /** The Samsung blur behind glass, when glassKind is SAMSUNG. */
        SemBlurBridge semBridge;
        /** The lens-mode BackgroundBlurDrawable behind glass on the lockscreen (debug.oulg.kgblurpath). */
        BackgroundBlurBridge compBridge;
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
        /** The row is scaled into a collapsed group's stack (see onPreDraw). */
        boolean stacked;
        /** The shade opening whose backdrop this card's veil was measured from (shadetone=3). */
        int toneOpen = -1;
        /** The veil measured for this card at the current shade opening (0: none needed). */
        int measuredVeil;
        /** The drawable path could not be built for this card: it keeps the Samsung blur. */
        boolean drawableFailed;
        void pushToneVeil() { pushToneVeil(measuredVeil); }
        void pushToneVeil(int veil) {
            try {
                if (compBridge != null) compBridge.setToneVeil(veil);
                if (semBridge != null) semBridge.setToneVeil(veil);
            } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SHADE_TONE_APPLY_FAILED", e); }
        }
        android.animation.ValueAnimator veilRamp;
        /**
         * A measurement lands a few frames into the opening, while the card already shows the veil of
         * the previous opening: switched in one frame, the card jumped in colour. It now ramps over
         * VEIL_RAMP_MS when both veils darken (or both lighten); a switch between a dark and a light
         * veil (theme change) still applies at once.
         */
        void applyMeasuredVeil(int veil) {
            int from = measuredVeil;
            measuredVeil = veil;
            if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
            View v = background.get();
            int[] ends = io.github.igorcv88.oneuiliquidglass.glass.VeilRamp.ends(from, veil);
            if (ends == null || v == null || !v.isShown() || (compBridge == null && semBridge == null)) {
                pushToneVeil(); invalidate(); return;
            }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofArgb(ends[0], ends[1]);
            a.setDuration(VEIL_RAMP_MS);
            a.addUpdateListener(an -> { pushToneVeil((Integer) an.getAnimatedValue()); invalidate(); });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) { pushToneVeil(); invalidate(); }
            });
            veilRamp = a;
            a.start();
        }
        // Hiding the region until the new measurement arrived made every opening pop in late; the
        // card keeps the veil of the previous opening meanwhile (measuredVeil persists), which is
        // already right when the backdrop has not changed.
        /** Last frame the card was scaled or faded (uptime ms); see onPreDraw. */
        long lastMotion = Long.MIN_VALUE / 2;
        /** Redraws once the card has been still for SETTLE_MS, so the tag comes back. */
        final Runnable settle = this::invalidate;
        /** Last ROW_FRAME line (debug.oulg.trace); logged again only when it changes. */
        String lastTrace;
        /** The background of the notification shelf, not of a row: the lockscreen's overflow pill. */
        final boolean pill;
        /** When this card's state was created (uptime ms): the arrival, for the optics timeline. */
        final long born = android.os.SystemClock.uptimeMillis();
        /** Draws of this card so far, glass or native (frame 1 is the first one on screen). */
        int frames;
        String lastOptics;
        boolean finalSeen;
        int opticsLines;
        /**
         * The optics timeline of a card on the lockscreen (bar state 1 or 2), one OPTICS line per draw
         * whose state differs from the previous one: what the compositor gets (path, tag profile,
         * region fade, ancestor scale) and the row state that decides it (heads-up, pinned, fallback
         * reason). OPTICS_FINAL marks the first draw in the final state (the lockscreen profile on
         * the drawable path, opaque and unscaled) with the time since the card's state was created;
         * firstFrame=true means it was born final. At most 60 lines per card.
         */
        void opticsTrace() {
            if (barState == null || (barState != Eligibility.BAR_KEYGUARD && barState != Eligibility.BAR_SHADE_LOCKED)) return;
            View v = background.get(), r = row.get();
            if (v == null || opticsLines >= 60) return;
            float a = 1f, sx = 1f, sy = 1f;
            for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
                View pv = (View) p; a *= pv.getAlpha(); sx *= pv.getScaleX(); sy *= pv.getScaleY();
            }
            int tag = compBridge != null ? compBridge.tagSent : semBridge != null ? semBridge.tagSent : -1;
            String path = compBridge != null ? "drawable" : semBridge != null ? "samsung" : glass != null ? String.valueOf(glassKind) : "native";
            boolean scaled = Math.abs(sx - 1f) > 0.001f || Math.abs(sy - 1f) > 0.001f;
            int want = pill ? -1 : SemBlurBridge.tagProfile(true, true);
            boolean fin = glass != null && compBridge != null && tag == want && a >= 0.999f && !scaled;
            Boolean hu = r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp");
            Boolean pinned = r == null ? null : Reflect.bool(r, "isPinned", "mIsPinned");
            String sig = "path=" + path + " tag=" + tag + " headsUp=" + hu + " pinned=" + pinned
                    + " alpha=" + (a >= 0.999f ? "1" : a <= 0.001f ? "0" : "partial") + " scaled=" + scaled
                    + " fallback=" + lastFallback + " stacked=" + stacked + " final=" + fin;
            long dt = android.os.SystemClock.uptimeMillis() - born;
            String id = Integer.toHexString(System.identityHashCode(v));
            if (!sig.equals(lastOptics)) {
                lastOptics = sig; opticsLines++;
                Probe.log("OPTICS", "viewId=" + id + " dt=" + dt + " sinceBar=" + (android.os.SystemClock.uptimeMillis() - barStateAt)
                        + " frame=" + frames + " barState=" + barState + (pill ? " pill=true" : "") + " " + sig
                        + (a < 0.999f ? " a=" + String.format(java.util.Locale.ROOT, "%.2f", a) : "")
                        + (scaled ? " s=" + String.format(java.util.Locale.ROOT, "%.3fx%.3f", sx, sy) : ""));
            }
            if (fin && !finalSeen) {
                finalSeen = true;
                Probe.log("OPTICS_FINAL", "viewId=" + id + " dt=" + dt + " sinceBar=" + (android.os.SystemClock.uptimeMillis() - barStateAt) + " frames=" + frames + " firstFrame=" + (frames <= 1) + (pill ? " pill=true" : ""));
            }
        }
        boolean pillReported;
        State(View background, View row) {
            this.background = new WeakReference<>(background); this.row = new WeakReference<>(row);
            pill = rowClass == null || !rowClass.isInstance(row);
        }
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
                        // Every card has a listener: log once per change, not once per card.
                        if (lastCapability == null || lastCapability != supported) {
                            lastCapability = supported;
                            Probe.log("BLUR_CAPABILITY_CHANGED", "enabled=" + supported);
                        }
                        if (!supported && glassKind == Backdrop.Kind.COMPOSITOR && compBridge == null) release();
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
            long started = Perf.start();
            try {
                View v = background.get(); if (v == null) return true;
                // A row scaled into the stack under a collapsed group: its blur region reaches the
                // compositor at a fractional, elliptical size that drops the corner tag (white behind
                // groups over a white app). Such rows carry no region; the glass draws the sliver.
                View sr = row.get();
                // Only a child of a group: the shade scales every top-level row while it closes, and
                // suspending those turned their region on and off frame by frame (device trace).
                // Outside the unlocked shade (the lockscreen) the earlier rule stands: any scaled row.
                boolean stacked = !pill && sr != null && (groupChild(sr) || barState == null || barState != Eligibility.BAR_SHADE)
                        && (Math.abs(sr.getScaleX() - 1f) > 0.005f || Math.abs(sr.getScaleY() - 1f) > 0.005f);
                if (stacked != this.stacked) {
                    this.stacked = stacked;
                    Probe.log("ROW_STACKED", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " stacked=" + stacked);
                    if (semBridge != null) semBridge.setSuspended(stacked);
                }
                if (compBridge != null || semBridge != null) {
                    // Ancestor alpha (a pull up from the lockscreen, or one that closes the shade)
                    // fades the drawn glass but not the compositor's blur region, which stayed
                    // behind as a ghost of the card. Ancestor scale (the stack shrinks as the shade
                    // closes) makes the region's size and radius fractional.
                    float a = 1f, sx = 1f, sy = 1f;
                    for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
                        View pv = (View) p; a *= pv.getAlpha(); sx *= pv.getScaleX(); sy *= pv.getScaleY();
                    }
                    // In the unlocked shade a partly transparent region turned whiter, not fainter (a
                    // white pill as the card faded): there it is whole or gone (shadefadecut).
                    float cut = barState != null && barState == Eligibility.BAR_SHADE ? Tuning.get().shadeFadeCut : 0f;
                    float fade = cut > 0f ? (a >= cut ? 1f : 0f) : a;
                    // The tag cannot survive a scaled region; with the finger nearly still the scale
                    // wobbled between 1 and 0.999 and the tag switched every frame. Once the card
                    // scales or fades it stays untagged until it has been still for SETTLE_MS.
                    long now = android.os.SystemClock.uptimeMillis();
                    if (Math.abs(sx - 1f) > 0.001f || Math.abs(sy - 1f) > 0.001f || a < 0.999f) {
                        lastMotion = now;
                        v.removeCallbacks(settle);
                        v.postDelayed(settle, SETTLE_MS + 20);
                    }
                    boolean untag = now - lastMotion < SETTLE_MS;
                    if (compBridge != null) {
                        try { compBridge.setScaled(untag); }
                        catch (ReflectiveOperationException e) { Probe.error("BLUR_DRAWABLE_SCALE_FAILED", e); }
                        if (compBridge.setRegionFade(stacked ? 0f : fade)) v.invalidate();
                    }
                    if (semBridge != null) {
                        try { semBridge.setScaled(untag); }
                        catch (ReflectiveOperationException e) { Probe.error("SEM_BLUR_SCALE_FAILED", e); }
                        semBridge.setFadeCut(cut > 0f && a < cut);
                        if (semBridge.setRegionFade(cut > 0f ? 1f : a)) v.invalidate();
                    }
                }
                if (Probe.trace) {
                    Object nd = null;
                    try { nd = backgroundField != null ? backgroundField.get(v) : null; } catch (IllegalAccessException ignored) { }
                    String line = FrameTrace.describe(v, sr, nd instanceof Drawable ? (Drawable) nd : null)
                            + " glass=" + (glass != null) + " suspended=" + stacked
                            + " path=" + (compBridge != null ? "drawable" : semBridge != null ? "samsung" : "-")
                            + " tag=" + (compBridge != null ? compBridge.tagSent : semBridge != null ? semBridge.tagSent : -1)
                            + " scaled=" + (compBridge != null ? compBridge.scaled() : semBridge != null && semBridge.scaled())
                            + (compBridge != null ? " drawableA=" + compBridge.drawable.getAlpha() : "");
                    if (!line.equals(lastTrace)) { lastTrace = line; FrameTrace.log("ROW_FRAME", v, line); }
                }
                // Measured as soon as the shade starts opening, before the card has a bridge or is
                // visible, so the veil is usually known by the time its region would show.
                if (!pill && toneOpen != shadeOpens && Tuning.get().shadeAdaptive
                        && Boolean.TRUE.equals(shadeExpanded) && barState != null && barState == Eligibility.BAR_SHADE && v.isShown()) {
                    // Once per shade opening: the veil is measured from the backdrop and kept.
                    toneOpen = shadeOpens;
                    ShadeTone.measure(v, this::applyMeasuredVeil);
                }
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
            finally { Perf.end(Perf.PREDRAW, started); }
            return true;
        }
        boolean eligible() {
            View v = background.get(), r = row.get();
            if (v == null || r == null || failed || !drawHook || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            if (pill && !pillSurface()) return false;
            return Eligibility.glass(enabled, v.isAttachedToWindow(), v.isHardwareAccelerated());
        }
        /** Diagnostic mirror of eligible() plus the compositor capability checked in material(). */
        String reason() {
            View v = background.get(), r = row.get();
            if (v == null || r == null) return "collected";
            if (failed) return "failed";
            if (!drawHook) return "drawHook=false";
            if (!v.isShown()) return "hidden";
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return "empty";
            if (pill && !pillSurface()) return "pill=off";
            String policy = Eligibility.reason(enabled, v.isAttachedToWindow(), v.isHardwareAccelerated());
            if (policy != null) return policy;
            return kind() != null ? null : "blur=unavailable";
        }
        Backdrop.Kind kind() {
            Backdrop.Kind k = Backdrop.choose(blurEnabled, samsungBlur, sharedBackdrop(), sampledRow());
            return k == Backdrop.Kind.SAMSUNG && drawablePath() && !drawableFailed && !drawableUnavailable ? Backdrop.Kind.COMPOSITOR : k;
        }
        /**
         * Lockscreen cards (and the shade over the lockscreen) take their blur from a
         * BackgroundBlurDrawable drawn with the glass, whatever cross-window blur reports (always
         * false on One UI 9, where Samsung's own blur still works). The Samsung blur installed on
         * the view covered the view's bounds, not the card's, and lagged it by a frame.
         */
        boolean drawablePath() {
            Tuning t = Tuning.get();
            // LSPosed-only: one path on every surface. A heads-up on the Samsung path got its region
            // a traversal late (applied after the draw that requested it) and swapped material when
            // it turned into a shade card.
            if (t.live) return true;
            if (t.kgBlurPath == 1 && t.sfRefract && barState != null
                    && (barState == Eligibility.BAR_KEYGUARD || barState == Eligibility.BAR_SHADE_LOCKED)) return true;
            // Expanded shade, unlocked: the Samsung region ignored the card's fade (a ghost of every
            // card on a pull up); the drawable's region takes it. Heads-up cards keep the Samsung path.
            View r = row.get();
            return t.shadeBlurPath == 1 && barState != null && barState == Eligibility.BAR_SHADE && Boolean.TRUE.equals(shadeExpanded)
                    && r != null && !Boolean.TRUE.equals(Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"));
        }
        /**
         * Rows whose background can be sampled and refracted, never in LSPosed-only mode (so with
         * debug.oulg.backdrop=hybrid, liveblur=0, capture or grid, and without sfrefract):
         * lockscreen rows refract the still wallpaper (redrawn on the GPU every
         * frame, no lag), heads-up rows a captured rim band. The app behind a heads-up lives in
         * another process and can only be captured periodically, which visibly lags behind motion.
         */
        boolean sampledRow() {
            View r = row.get(), v = background.get();
            if (r == null || v == null || sharedBackdrop()) return false;
            String mode = Tuning.get().backdrop;
            if (mode.equals("off") || !GlassDrawable.refractionAvailable()) return false;
            // The compositor refracts these cards itself: keep the live Samsung blur, no capture.
            // LSPosed-only: a capture lands frames late, so the rim lagged motion behind the card.
            if (Tuning.get().sfRefract || Tuning.get().live) return false;
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
        /**
         * The shelf takes glass only as the lockscreen's overflow pill, through the drawable path
         * (its region follows the pill's fade like the cards'); in the shade it keeps SystemUI's own.
         */
        boolean pillSurface() {
            Tuning t = Tuning.get();
            return t.pillGlass && barState != null && barState == Eligibility.BAR_KEYGUARD && drawablePath() && !drawableFailed && !drawableUnavailable;
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
            boolean pressed = false;
            for (int state : original.getState()) if (state == android.R.attr.state_pressed) pressed = true;
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
                nativePending = false;
                Backdrop backdrop;
                if (kind == Backdrop.Kind.COMPOSITOR && drawablePath()) {
                    try { backdrop = compBridge = BackgroundBlurBridge.create(v, true); }
                    catch (ReflectiveOperationException | RuntimeException e) {
                        // The drawable path is the default on every surface in LSPosed-only mode and
                        // was never exercised on the pop-up: if it cannot be built, this card keeps
                        // the Samsung blur for its lifetime (never per frame, which would flash). A
                        // missing factory or setter holds for the whole process.
                        if (e instanceof ReflectiveOperationException) drawableUnavailable = true;
                        drawableFailed = true;
                        Probe.error("BLUR_DRAWABLE_FALLBACK", e);
                        if (pill || !samsungBlur || !SemBlurBridge.supports(Reflect.read(v, "mCornerRadii"))) {
                            release(); return fallback("drawableFailed");
                        }
                        kind = Backdrop.Kind.SAMSUNG;
                        backdrop = semBridge = SemBlurBridge.create(v);
                    }
                } else {
                    backdrop = kind == Backdrop.Kind.SHARED ? new SharedBackdrop()
                            : kind == Backdrop.Kind.SAMPLED ? sampledBackdrop(v, source)
                            : kind == Backdrop.Kind.SAMSUNG ? (semBridge = SemBlurBridge.create(v))
                            : BackgroundBlurBridge.create(v);
                }
                if (compBridge != null) { SemBlurBridge.clearNative(v); compBridge.setInvalidator(this::invalidate); }
                if (compBridge != null && pill) compBridge.setPill(true);
                // A new bridge takes the veil measured for this opening.
                pushToneVeil();
                if (semBridge != null) semBridge.setSuspended(stacked);
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
            boolean live = Tuning.get().live && compBridge != null;
            if (live && dark) fill = 0;
            int tone = live && dark ? (lensSurface() ? spec.liveDarkColor : spec.liveShadeDarkColor)
                    : glass.hybrid() || kind == Backdrop.Kind.SAMSUNG || compBridge != null ? (dark ? spec.samsungDarkColor : spec.samsungLightColor)
                    : kind == Backdrop.Kind.SAMPLED ? (dark ? spec.captureDarkTint : spec.captureLightTint)
                    : dark ? spec.darkBlurColor : spec.lightBlurColor;
            glass.setPressed(pressed);
            refreshLens();
            glass.configure(original, shape(), fill, tone);
            if (live) liveState(v, fill);
            if (lastFallback != null) { lastFallback = null; Probe.log("NATIVE_FALLBACK", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " reason=none"); }
            if (pill && !pillReported) {
                pillReported = true;
                View sh = row.get();
                Probe.log("PILL_GLASS", "bg=" + v.getClass().getName() + " shelf=" + (sh == null ? "null" : sh.getClass().getName())
                        + " size=" + v.getWidth() + "x" + v.getHeight() + " radii=" + java.util.Arrays.toString(shape())
                        + " nativeAlpha=" + original.getAlpha() + " native=" + original.getClass().getName() + " blur=" + Tuning.get().pillBlur);
            }
            if (!materialReported) {
                materialReported = true;
                Probe.material(v, row.get(), original, String.valueOf(kind), fill, tone);
            }
            return glass;
        }
        /** The native background draws this frame; logged when the reason changes. */
        GlassDrawable fallback(String why) {
            why = String.valueOf(why);
            if (!why.equals("hidden")) restorePendingNative();
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
        /** Whether the card is a pop-up or lockscreen card (lens surface) rather than a shade card. */
        boolean lensSurface() {
            View r = row.get();
            return !pill && Eligibility.lens(barState, shadeExpanded, r == null ? null : Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"));
        }
        /** Last LIVE_STATE line of this card; logged again only when the material it describes changes. */
        String lastLiveState;
        /**
         * What an LSPosed-only card asks the compositor for: the surface, the radius and colour sent to
         * the blur region, the fill, the cross-window capability (false on One UI 9 even when the blur
         * renders). Fades are left out: they change every frame of an animation. It says what was requested, not what reached the screen.
         */
        void liveState(View v, int fill) {
            int px = compBridge.requestedPx(), tint = compBridge.requestedTint();
            String surface = pill ? "pill" : lensSurface() ? (barState != null && barState != Eligibility.BAR_SHADE ? "lockscreen" : "popup") : "shade";
            String line = "surface=" + surface + " radius=" + px + " tint=" + Integer.toHexString(tint) + " fill=" + Integer.toHexString(fill)
                    + " veil=" + Integer.toHexString(measuredVeil) + " crossBlur=" + blurEnabled;
            if (line.equals(lastLiveState)) return;
            lastLiveState = line;
            Tuning t = Tuning.get();
            Probe.log("LIVE_STATE", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " " + line
                    + " live=" + t.live + " liveradius=" + t.liveRadius + " liveshaderadius=" + t.liveShadeRadius + " liveoptics=" + t.liveOptics);
        }
        void refreshLens() {
            View r = row.get();
            Boolean keyguard = Reflect.bool(r, "isOnKeyguard", "mOnKeyguard");
            // The pill is plain light glass: no lens, no compositor material.
            boolean kgSurface = !pill && Eligibility.keyguardOptics(barState, Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"));
            boolean lens = !pill && Eligibility.lens(barState, shadeExpanded, keyguard);
            // The edge shader steps back only for a card the compositor demonstrably lights: profile
            // 1 requested, a tag that survives every program (radius and shape), and a library whose
            // profile-1 optics are confirmed (debug.oulg.sf.optics). Otherwise it keeps its lighting.
            if (glass != null) {
                float[] radii = shape();
                boolean tagged = SemBlurBridge.tagProfile(lens, kgSurface) == 1 && SemBlurBridge.reliableTag(radii[0])
                        && (compBridge != null || (semBridge != null && semBridge.tagsShape(radii)));
                glass.setCompositorLit(tagged && Tuning.get().sfOptics >= 9);
            }
            if (compBridge != null) {
                try { compBridge.setOptics(kgSurface, lens); }
                catch (ReflectiveOperationException | RuntimeException e) { Probe.error("BLUR_DRAWABLE_LENS_FAILED", e); }
                return;
            }
            if (semBridge == null) return;
            try { semBridge.setOptics(kgSurface, lens); }
            catch (ReflectiveOperationException | RuntimeException e) { Probe.error("SEM_BLUR_LENS_FAILED", e); }
        }
        void release() {
            View v = background.get();
            release(v == null || v.isShown());
        }
        /**
         * restore=false (the card is being hidden) clears the blur instead of handing SystemUI's own
         * back: the compositor keeps a blur region for a frame or two after its view stops drawing,
         * and the native radius-180 blur would show on the card for those frames. The native blur
         * is handed back later if the card is shown without glass.
         */
        void release(boolean restore) {
            if (glass == null) return;
            // The blur guard also blocked calls aimed at the row while this material was managed.
            boolean compositor = glassKind == Backdrop.Kind.SAMSUNG || glass.hybrid() || compBridge != null;
            boolean drawable = compBridge != null;
            if (!restore && semBridge != null) semBridge.clearOnRelease();
            glass.release(); glass = null; glassKind = null; glassSource = null; semBridge = null; compBridge = null;
            Probe.log("GLASS_RELEASED", "native=" + restore);
            View r = row.get(), bg = background.get();
            if (restore && compositor && r != null) SemBlurBridge.restoreNative(r);
            // The Samsung blur was taken off the background itself for the drawable path.
            if (restore && drawable && bg != null) SemBlurBridge.restoreNative(bg);
            nativePending = compositor && !restore;
        }
        /** The native blur was cleared on a hidden release and is still owed if no glass comes back. */
        boolean nativePending;
        void restorePendingNative() {
            if (!nativePending) return;
            nativePending = false;
            View v = background.get(), r = row.get();
            if (v != null) SemBlurBridge.restoreNative(v);
            if (r != null) SemBlurBridge.restoreNative(r);
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
            long started = Perf.start();
            try {
                View row = findHost(v); if (row == null) return;
                State s = state(v, row);
                Object current = backgroundField.get(v); if (!(current instanceof Drawable)) return;
                s.frames++;
                GlassDrawable glass = s.material((Drawable) current);
                s.opticsTrace();
                if (glass == null) return;
                if (s.scratch == null) s.scratch = new android.graphics.RenderNode("oulg-native-bounds");
                Canvas scratch = s.scratch.beginRecording(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()));
                p.setObjectExtra("oulgCanvas", p.args[0]);
                p.setObjectExtra("oulgGlass", glass);
                p.args[0] = scratch;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                State s = states.get(v); if (s != null) { s.failed = true; s.release(); }
                Probe.error("DRAW_SWAP_FAILED", e);
            } finally { Perf.end(Perf.DRAW_BEFORE, started); }
        }
        @Override protected void afterHookedMethod(MethodHookParam p) {
            Object real = p.getObjectExtra("oulgCanvas"); if (!(real instanceof Canvas)) return;
            long started = Perf.start();
            try { composeGlass(p, (Canvas) real); } finally { Perf.end(Perf.DRAW_AFTER, started); }
        }
        private void composeGlass(MethodHookParam p, Canvas real) {
            View v = (View) p.thisObject;
            State s = states.get(v);
            if (s != null && s.scratch != null) { s.scratch.endRecording(); s.scratch.discardDisplayList(); }
            p.args[0] = real;
            Canvas canvas = real;
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

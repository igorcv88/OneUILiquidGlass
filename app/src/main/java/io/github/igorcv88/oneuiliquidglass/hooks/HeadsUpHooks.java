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
import io.github.igorcv88.oneuiliquidglass.glass.BackdropMotion;
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
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;
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
    /** Draw-time evidence is independent of State so an unmodified Samsung layer is still counted. */
    private final WeakHashMap<View, LayerSample> layerSamples = new WeakHashMap<>();
    private long layerDrawOrder;
    private static final class LayerSample {
        int draws;
        long loggedAt;
        String last = "";
    }
    private final Set<Method> installed = Collections.newSetFromMap(new java.util.HashMap<>());
    private Class<?> rowClass;
    private Field backgroundField;
    private Field clipTopField, clipBottomField, actualHeightField, expandRunningField;
    private boolean drawHook;
    private boolean shadeHook;
    private QuickSettingsHooks quickSettings;
    private boolean blurEnvironmentReported;
    private final boolean samsungBlur = SemBlurBridge.available();
    private final GlassSpec spec = new GlassSpec();
    private Boolean shadeExpanded;
    /** Counts shade openings: the generation of the backdrop tone (shadetone=3). */
    private int shadeOpens;
    /**
     * The backdrop of the current shade opening in luminance bands, from one capture asked for on
     * its first frame (ShadeTone.prefetch); null until it lands, and for any other generation.
     */
    private ToneGrid shadeGrid;
    /** The current opening's scene: its whole backdrop read as light; null until its capture lands. */
    private Boolean shadeScene;
    /** Uptime of the current opening's first frame, and its counters for SHADE_OPEN_END. */
    private long shadeOpenAt;
    private int shadeCards, shadeEarlyDraws;
    /**
     * A capture of the backdrop taken when a touch went down at the top edge with the shade closed,
     * the start of a pull-down (preOpen); a shade opening within PRE_OPEN_MAX_MS adopts it on its
     * first frame. The status-bar appearance used as the first-frame estimate in round 36 read a dark
     * toolbar over a white page as a dark backdrop (device log, 25/10) and is gone.
     */
    private ToneGrid preGrid;
    private long preRequestAt;
    /**
     * The touch gesture a pre-open capture belongs to (MotionEvent.getDownTime, the same for every
     * receiver that sees it), and the current gesture: its down time, whether it lifted and when.
     * A capture is adopted only by an opening that this same gesture drives (Eligibility.preOpenValid).
     */
    private long preGesture, preGridGesture;
    private volatile long gestureDown = Long.MIN_VALUE, gestureUpAt;
    private volatile boolean gestureUp;
    /** The shade panel view (NotificationPanelViewController.mView), the anchor of pre-open captures. */
    private WeakReference<View> panelView;
    private static final long PRE_OPEN_MAX_MS = 1500;
    /** How long after its finger lifts a gesture may still be the one opening the shade (a fling). */
    private static final long PRE_OPEN_LIFT_MS = 250;
    /** Fractions of the screen where a touch down starts a system gesture: the bottom strip, each side edge. */
    private static final float SYSTEM_BOTTOM = 0.045f, SYSTEM_SIDE = 0.04f;
    /** Fraction of the screen height from the top where a touch down may start a pull-down. */
    private static final float PRE_OPEN_EDGE = 0.06f;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
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
    /** BLUR_FACTORY (the blur drawable factory works on this firmware) was reported. */
    private boolean reportedCapability;
    /** Last cross-window blur capability logged (BLUR_CAPABILITY_CHANGED), across all cards. */
    private Boolean lastCapability;
    /** enabled == null: preferences unreadable, resolve through the module's ConfigProvider. */
    public HeadsUpHooks(ClassLoader loader, Boolean enabled) {
        this.loader = loader; this.enabled = Boolean.TRUE.equals(enabled); this.configPending = enabled == null;
    }
    private void resolveConfig(android.content.Context context) {
        io.github.igorcv88.oneuiliquidglass.config.ConfigClient.start(context, value -> {
            enabled = value;
            if (quickSettings != null) quickSettings.refresh();
            if (!shadeConfigEnabled()) { panelDim(false); restoreHeader(); orphanTone(); }
            else if (shadeGrid != null) headerTone(shadeGrid);
            for (State state : new ArrayList<>(states.values())) {
                if (!enabled || !state.surfaceEnabled()) state.release();
                state.invalidate();
            }
        });
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
                        if ("startAppearAnimation".equals(name) || "onAppearAnimationFinished".equals(name)) exitEvent((View) p.thisObject, name, p.args);
                        observeRow((View) p.thisObject, name);
                        // A new row inflates when a notification arrives; survey windows while its pop-up is on screen.
                        if ("onFinishInflate".equals(name)) WindowSurvey.schedule("rowInflate");
                    }
                }
                catch (RuntimeException | LinkageError e) { Probe.error("ROW_PROBE_FAILED", e); }
            }
        };
        for (String n : new String[]{"onFinishInflate", "setHeadsUp", "setPinned", "setHeadsUpAnimatingAway", "startAppearAnimation", "onAppearAnimationFinished", "setUserExpanded", "setOnKeyguard", "onAttachedToWindow"}) hook(rowClass, n, rowEvent);
        // A cancelled appear/disappear animation (AOSP ActivatableNotificationView.cancelAppearDrawing,
        // and the animator cancel it runs) may end without onAppearAnimationFinished: the card stops
        // leaving at once instead of at CardExit.MAX_EXIT_MS. It only ends the exit; it releases nothing.
        XC_MethodHook exitCancel = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { if (rowClass != null && rowClass.isInstance(p.thisObject)) exitEvent((View) p.thisObject, ((Method) p.method).getName(), p.args); }
                catch (RuntimeException | LinkageError e) { Probe.error("EXIT_CANCEL_FAILED", e); }
            }
        };
        for (String n : new String[]{"cancelAppearDrawing", "cancelAppearAnimation"}) hook(rowClass, n, exitCancel);
        new HeaderGapHooks(loader, rowClass, () -> enabled).install();
        // QS is an independent opt-in surface: no notification-state or drawing hooks are shared.
        quickSettings = new QuickSettingsHooks(loader, () -> enabled, this::resolveConfig);
        quickSettings.install();
        // ConfigClient dispatches immediately, but Tuning publishes its new material on a
        // worker. Invalidating notifications at CONFIG_DISPATCH can therefore repaint with
        // the previous shadelightglass value and leave a stable, visible card unchanged until
        // its next unrelated pre-draw (DZIF PR84 trace, 28/10). Redraw after publication too.
        Tuning.onChanged(() -> {
            // A saved panel knob also needs the last shade-window SemBlurInfo reasserted. Card
            // invalidation alone cannot update Samsung's own radius/colour adjustment.
            main.post(this::refreshPanelBlur);
            int visible = 0;
            for (State state : new ArrayList<>(states.values())) {
                View v = state.background.get();
                if (v == null || !v.isAttachedToWindow() || !v.isShown()) continue;
                state.invalidate();
                visible++;
            }
            Probe.log("SHADE_TUNING_APPLY", "generation=" + Tuning.generation
                    + " revision=" + io.github.igorcv88.oneuiliquidglass.config.ConfigClient.revision()
                    + " visibleInvalidated=" + visible + " repaint=unverified");
        });
        // A task change re-measures the shown surfaces at once (periodic sampling stays the fallback).
        BackdropEvents.install(loader);
        BackdropEvents.add(this::backdropChanged);
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
        installTouchSignal();
        installTextColorGuard();
        // A child row with its own glass keeps its own tone; one drawn inside its group's card
        // (no glass of its own) is walked with the group's.
        // In the open shade a child owns its tone only if its own card took this opening's tone: a
        // minimised group's child kept its glass but its background is not drawn, so its tone never
        // ran and its text stayed white on the group's light glass (TEXT_ROW app_name_text
        // ffffffff swapped=false, device log 26/10).
        Foreground.ownsTone = child -> {
            boolean shade = Boolean.TRUE.equals(shadeExpanded);
            for (State st : states.values())
                if (st.row.get() == child && st.glass != null && (!shade || st.toneOpen == shadeOpens)) return true;
            return false;
        };
        // Shade state is only known once SystemUI constructs the controller, after this point.
        Probe.log("READY", "mode=" + (enabled ? "glass" : "probe") + " drawHook=" + drawHook + " shadeHook=" + shadeHook + " samsungBlur=" + samsungBlur);
        if (enabled && (!drawHook || !shadeHook)) Probe.log("GLASS_UNAVAILABLE", "drawHook=" + drawHook + " shadeHook=" + shadeHook);
    }
    /**
     * Touches seen by SystemUI's input receivers, its gesture-navigation monitor included, feed
     * BackdropMotion, which hides the captured lens band of LSPosed-only pop-ups while the content
     * behind them may move. Observation only: the event is not changed or consumed.
     */
    /**
     * Colour writes on views whose tone the module swapped (Foreground.redirect): SystemUI sets a
     * notification's text colour on bind, in the heads-up-to-shade transformation and, for one group
     * row, on every frame. Redirected in the same call instead of repaired a frame later. Only views
     * the module swapped (a lookup in a map that is empty while nothing is swapped) are affected;
     * every other call passes untouched.
     */
    private void installTextColorGuard() {
        try {
            Set<?> text = XposedBridge.hookAllMethods(android.widget.TextView.class, "setTextColor", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Foreground.applying || !Foreground.tracking() || p.args.length != 1) return;
                    long started = Perf.start(Perf.TEXT_GUARD);
                    try {
                        Object a = p.args[0];
                        if (a instanceof Integer) {
                            Integer c = Foreground.interceptColor((android.widget.TextView) p.thisObject, (Integer) a);
                            if (c != null) p.args[0] = c;
                        } else if (a instanceof android.content.res.ColorStateList) {
                            android.content.res.ColorStateList c = Foreground.interceptList((View) p.thisObject, (android.content.res.ColorStateList) a);
                            if (c != null) p.args[0] = c;
                        }
                    } catch (RuntimeException | LinkageError e) { Probe.error("TEXT_GUARD_FAILED", e); }
                    finally { Perf.end(Perf.TEXT_GUARD, started); }
                }
            });
            Set<?> tint = XposedBridge.hookAllMethods(android.widget.ImageView.class, "setImageTintList", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Foreground.applying || !Foreground.tracking() || p.args.length != 1) return;
                    try {
                        if (!(p.args[0] instanceof android.content.res.ColorStateList)) return;
                        android.content.res.ColorStateList c = Foreground.interceptList((View) p.thisObject, (android.content.res.ColorStateList) p.args[0]);
                        if (c != null) p.args[0] = c;
                    } catch (RuntimeException | LinkageError e) { Probe.error("TEXT_GUARD_FAILED", e); }
                }
            });
            XposedBridge.hookAllMethods(android.widget.ImageView.class,"setImageDrawable",new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(Foreground.applying||!Foreground.trackingChevrons()||p.args.length!=1)return;
                    if(p.args[0] instanceof android.graphics.drawable.Drawable)
                        Foreground.beforeChevronDrawable((android.widget.ImageView)p.thisObject,(android.graphics.drawable.Drawable)p.args[0]);
                }
            });
            Probe.log("TEXT_GUARD", "textHooks=" + text.size() + " tintHooks=" + tint.size());
        } catch (RuntimeException | LinkageError e) { Probe.error("TEXT_GUARD_INSTALL_FAILED", e); }
    }
    private void installTouchSignal() {
        try {
            Class<?> receiver = Class.forName("android.view.InputEventReceiver");
            Set<?> hooks = XposedBridge.hookAllMethods(receiver, "dispatchInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // Every input event of every SystemUI window passes here. The signal feeds the
                    // lens band, the pop-up tone and the pre-open capture, all off with the glass.
                    if (!enabled) return;
                    Object e = p.args.length > 1 ? p.args[1] : null;
                    if (!(e instanceof android.view.MotionEvent)) return;
                    long started = Perf.start(Perf.TOUCH);
                    try { touch(p, (android.view.MotionEvent) e); } finally { Perf.end(Perf.TOUCH, started); }
                }
                private void touch(MethodHookParam p, android.view.MotionEvent m) {
                    if (!m.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) return;
                    // The middle of the screen with the shade closed belongs to the app below, not to SystemUI.
                    float h = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
                    boolean outside = barState != null && barState == Eligibility.BAR_SHADE && !Boolean.TRUE.equals(shadeExpanded)
                            && m.getRawY() > h * 0.30f && m.getRawY() < h * 0.80f;
                    int action = m.getActionMasked();
                    long down = m.getDownTime();
                    if (action == android.view.MotionEvent.ACTION_DOWN && down != gestureDown) { gestureDown = down; gestureUp = false; }
                    else if ((action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) && down == gestureDown && !gestureUp) {
                        gestureUp = true; gestureUpAt = android.os.SystemClock.uptimeMillis();
                    }
                    if (action == android.view.MotionEvent.ACTION_DOWN && m.getRawY() < h * PRE_OPEN_EDGE
                            && barState != null && barState == Eligibility.BAR_SHADE && !Boolean.TRUE.equals(shadeExpanded)) main.post(() -> preOpen(down));
                    boolean was = BackdropMotion.active();
                    // The bottom navigation strip and the side back edges: system gestures that move the whole app.
                    float w = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
                    boolean systemZone = m.getRawY() > h * (1f - SYSTEM_BOTTOM) || m.getRawX() < w * SYSTEM_SIDE || m.getRawX() > w * (1f - SYSTEM_SIDE);
                    BackdropMotion.onTouch(p.thisObject, p.thisObject.getClass().getName(), m.getActionMasked(), m.getRawX(), m.getRawY(),
                            android.os.SystemClock.uptimeMillis(), outside, systemZone, Tuning.get().lensTransition);
                    if (!was && BackdropMotion.active()) Probe.log("TOUCH_SIGNAL", "verified=true receiver=" + p.thisObject.getClass().getName());
                }
            });
            Set<?> disposals = XposedBridge.hookAllMethods(receiver, "dispose", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (BackdropMotion.onDisposed(p.thisObject)) Probe.log("TOUCH_SIGNAL", "verified=false reason=disposed receiver=" + p.thisObject.getClass().getName());
                }
            });
            // Without the disposal hook a monitor could die and leave the signal proven: no signal then.
            if (!hooks.isEmpty() && !disposals.isEmpty()) BackdropMotion.setHooked();
            Probe.log("TOUCH_SIGNAL", "hooked=" + !hooks.isEmpty() + " disposeHooked=" + !disposals.isEmpty());
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) { Probe.error("TOUCH_SIGNAL_FAILED", e); }
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
                    long started = Perf.start(Perf.BLUR_GUARD);
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
                        if (p.args[0] != null && v.getRootView() == v && v.getClass().getName().endsWith("NotificationShadeWindowView")) panelBlur(v, p.args[0]);
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
                            long started = Perf.start(Perf.BLUR_MUTATOR);
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
    /**
     * A touch down at the top edge with the shade closed over an app: a pull-down may follow. The
     * backdrop is captured now, ~100 ms before the shade's first frame, so cards are born in the right
     * style. On the main thread; one request per gesture, bound to it.
     */
    private boolean shadeConfigEnabled() {
        return io.github.igorcv88.oneuiliquidglass.config.ProductFlags.shadeContrastEnabled(enabled,
                io.github.igorcv88.oneuiliquidglass.config.ConfigClient::flag);
    }
    private void preOpen(long gesture) {
        Tuning t = Tuning.get();
        if (!shadeConfigEnabled() || !t.shadeAdaptive || barState == null || barState != Eligibility.BAR_SHADE || Boolean.TRUE.equals(shadeExpanded)) return;
        if (gesture == preGesture || gesture != gestureDown) return;
        View anchor = panelView == null ? null : panelView.get();
        if (anchor == null || !anchor.isAttachedToWindow()) return;
        long requested = android.os.SystemClock.uptimeMillis();
        preGesture = gesture; preRequestAt = requested;
        // A newer gesture's capture replaces an older one that was never adopted.
        preGrid = null;
        ShadeTone.prefetch(anchor, -1, grid -> {
            if (grid == null || gesture != preGesture) return;
            // Opened by this gesture while the capture was in flight: adopted if the opening has none yet.
            if (Boolean.TRUE.equals(shadeExpanded)) {
                if (shadeOpenAt >= requested && (shadeGrid == null || shadeGrid.generation != shadeOpens) && preValid(gesture, grid))
                    adoptGrid(grid.forGeneration(shadeOpens), "preopen");
                return;
            }
            preGrid = grid; preGridGesture = gesture;
        });
    }
    private boolean preValid(long gesture, ToneGrid grid) {
        long now = android.os.SystemClock.uptimeMillis();
        return Eligibility.preOpenValid(gesture, gestureDown, gestureUp, now - gestureUpAt, now - grid.takenAt, PRE_OPEN_LIFT_MS, PRE_OPEN_MAX_MS);
    }
    private void adoptGrid(ToneGrid grid, String state) {
        Tuning t = Tuning.get();
        float whole = grid.whole();
        long now = android.os.SystemClock.uptimeMillis();
        // A new opening decides from its midpoint; a resample keeps the decided scene's hysteresis.
        boolean scene = whole >= (t.styleLo + t.styleHi) * 0.5f;
        sceneGate.reset(); sceneGate.offer(scene, now);
        adoptGrid(grid, state, scene);
    }
    private void adoptGrid(ToneGrid grid, String state, boolean scene) {
        shadeGrid = grid;
        long now = android.os.SystemClock.uptimeMillis();
        // One decision for the whole stack (the scene); a card only departs from it where its own
        // bands are clearly light or dark (VeilMath.classify).
        float whole = grid.whole();
        Boolean previousScene = shadeScene;
        shadeScene = scene;
        Probe.log("SHADE_PREFETCH", "generation=" + grid.generation + " state=" + state + " tFromOpen=" + (now - shadeOpenAt)
                + " age=" + (now - grid.takenAt) + " earlyDraws=" + shadeEarlyDraws + " top=" + grid.top + " band=" + grid.band
                + " scene=" + (shadeScene ? "light" : "dark") + " sceneLuma=" + String.format(java.util.Locale.ROOT, "%.2f", whole)
                + " previousScene=" + (previousScene == null ? "none" : previousScene ? "light" : "dark"));
        if (Probe.trace && shadeScene) {
            // Theme-tone labels outside the rows (the minimized group's title, section headers),
            // once the shade has finished opening, when the shelf and the group headers are shown.
            View pv = panelView == null ? null : panelView.get();
            int gen = shadeOpens;
            if (pv != null) pv.postDelayed(() -> {
                if (gen != shadeOpens || !Boolean.TRUE.equals(shadeExpanded)) return;
                for (String d : Foreground.survey(pv.getRootView(), ShadeTone.lightText(), 30)) Probe.log("TEXT_OUTSIDE", d);
                for (State st : new ArrayList<>(states.values())) {
                    View r = st.row.get();
                    if (r != null && r.isShown()) for (String d : Foreground.rowSurvey(r, ShadeTone.lightText(), 20))
                        Probe.log("TEXT_ROW", "rowId=" + Integer.toHexString(System.identityHashCode(r)) + " pill=" + st.pill + " " + d);
                }
            }, 700);
        }
        for (State st : new ArrayList<>(states.values())) st.invalidate();
        headerTone(grid);
        orphanTone();
        View anchor = panelView == null ? null : panelView.get();
        int gen = shadeOpens;
        // Rows bound or shown after the capture (a group minimised meanwhile) get the same pass.
        if (anchor != null) for (long delay : new long[] { 300, 1000 })
            anchor.postDelayed(() -> { if (gen == shadeOpens) orphanTone(); }, delay);
        panelDim(shadeConfigEnabled() && Tuning.get().textSwap && shadeScene && ShadeTone.lightText());
        scheduleResample(RESAMPLE_MS);
    }
    /**
     * In-session resampling (SHADE_RESAMPLE). The opening's capture decides the first tone; while the
     * shade stays open, the backdrop is captured again every RESAMPLE_MS (one low-resolution
     * capture below the shade window, never per frame, never while the shade is hidden), so a real
     * change behind it (the app turning from white to black) reaches the header, the cards and their
     * text. The scene changes only through sceneGate: a different reading must be confirmed by the
     * next capture, taken CONFIRM_MS later; a single transient frame or sampling noise changes
     * nothing. Each capture also feeds the header's own gated decision for its band.
     */
    static final long RESAMPLE_MS = 900, ACTIVE_RESAMPLE_MS = 250, CONFIRM_MS = 150, SCENE_DWELL_MS = 600;
    /** Faster right after a touch (an app switch, a scroll may have changed what is behind). */
    static long resampleInterval(long now) {
        if (BackdropEvents.burst(now)) return BURST_RESAMPLE_MS;
        return now - BackdropMotion.lastActive() < POPUP_ACTIVE_MS ? ACTIVE_RESAMPLE_MS : RESAMPLE_MS;
    }
    /** Right after a task change the backdrop is still settling: sample this often for BackdropEvents.BURST_MS. */
    static final long BURST_RESAMPLE_MS = 100;
    /**
     * Another task came to the front or the stack changed (BackdropEvents): the open shade captures
     * now, and every shown pop-up measures on its next frame, instead of waiting for their periodic
     * samples. Main thread.
     */
    private void backdropChanged() {
        if (Boolean.TRUE.equals(shadeExpanded) && shadeGrid != null && resampleCancel == null) {
            main.removeCallbacks(resample);
            resampleShade();
        }
        for (State st : new ArrayList<>(states.values())) {
            View v = st.background.get();
            if (v == null || !SURFACE_POPUP.equals(st.toneSurface) || !v.isShown()) continue;
            st.popupMeasureAt = 0;
            v.removeCallbacks(st.popupPoke);
            v.postOnAnimation(st.popupPoke);
        }
    }
    private final io.github.igorcv88.oneuiliquidglass.glass.ToneGate sceneGate =
            new io.github.igorcv88.oneuiliquidglass.glass.ToneGate(CONFIRM_MS, SCENE_DWELL_MS);
    private final Runnable resample = this::resampleShade;
    private Runnable resampleCancel;
    private int resampleTicket;
    private void scheduleResample(long delay) {
        main.removeCallbacks(resample);
        if (!Boolean.TRUE.equals(shadeExpanded) || shadeGrid == null || resampleCancel != null) return;
        main.postDelayed(resample, Math.max(16, delay));
    }
    private void stopResample() {
        main.removeCallbacks(resample);
        resampleTicket++;
        if (resampleCancel != null) resampleCancel.run();
        resampleCancel = null;
    }
    private void resampleShade() {
        Tuning t = Tuning.get();
        if (!shadeConfigEnabled() || !t.shadeAdaptive || !Boolean.TRUE.equals(shadeExpanded) || shadeGrid == null
                || barState == null || barState != Eligibility.BAR_SHADE) return;
        View anchor = panelView == null ? null : panelView.get();
        if (anchor == null || !anchor.isAttachedToWindow()) return;
        if (!anchor.isShown()) { scheduleResample(RESAMPLE_MS); return; }
        int generation = shadeOpens, ticket = ++resampleTicket;
        long requested = android.os.SystemClock.uptimeMillis();
        boolean[] landed = {false};
        Runnable cancel = ShadeTone.prefetch(anchor, generation, grid -> {
            landed[0] = true;
            if (ticket != resampleTicket) return; // Closed or superseded meanwhile: a late capture is dropped.
            resampleCancel = null;
            if (generation != shadeOpens || !Boolean.TRUE.equals(shadeExpanded) || shadeGrid == null) return;
            // No capture possible (secure content, capture unavailable): no loop for this opening.
            if (grid == null) { Probe.log("SHADE_RESAMPLE", "generation=" + generation + " state=unavailable"); return; }
            long now = android.os.SystemClock.uptimeMillis();
            try { if (grid.takenAt >= shadeGrid.takenAt) offerGrid(grid, now - requested); }
            catch (RuntimeException | LinkageError e) { Probe.error("SHADE_RESAMPLE_FAILED", e); return; }
            boolean pending = sceneGate.pending() || headerInk.pending() || shadeHeaderGate.pending();
            long idle = resampleInterval(now);
            scheduleResample(pending ? Math.max(CONFIRM_MS, Math.min(idle, sceneGate.pending() ? sceneGate.confirmIn(now) : CONFIRM_MS)) : idle);
        });
        // A capture that cannot be taken answers synchronously: nothing is in flight then.
        if (!landed[0]) resampleCancel = cancel;
    }
    private void offerGrid(ToneGrid grid, long age) {
        long now = android.os.SystemClock.uptimeMillis();
        float whole = grid.whole();
        if (!Float.isFinite(whole) || whole < 0f || whole > 1f) return;
        boolean light = ShadeTone.lightBackdrop(whole, shadeScene);
        boolean pendingBefore = sceneGate.pending();
        Tuning t = Tuning.get();
        boolean strong = io.github.igorcv88.oneuiliquidglass.glass.ToneGate.strong(whole, (t.styleLo + t.styleHi) * 0.5f);
        boolean commit = sceneGate.offer(light, now, strong) && shadeScene != null && light != shadeScene;
        if (commit || sceneGate.pending() != pendingBefore)
            Probe.log("SHADE_RESAMPLE", "generation=" + shadeOpens + " luma=" + String.format(java.util.Locale.ROOT, "%.2f", whole)
                    + " reading=" + (light ? "light" : "dark") + " decided=" + (shadeScene == null ? "none" : shadeScene ? "light" : "dark")
                    + " commit=" + commit + " strong=" + strong + " pending=" + sceneGate.pending() + " sampleAgeMs=" + age);
        // The header decides its own band with its own gate; the same capture serves both.
        headerTone(grid);
        if (commit) adoptGrid(grid, "resample", light);
    }
    /** Rows given the scene's text tone by orphanTone, restored when the shade closes. */
    private final java.util.Set<State> orphans = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * Shown rows in the open shade whose own card took no tone this opening take the scene's text
     * tone: a minimised group's rows have no card drawn (radii 0), so their tone never ran and their
     * app_name_text stayed white on the group's light glass (oulg43: two rows, swapped=false). Every
     * card's tone comes from the same scene reading, so this is the tone they would have taken.
     */
    private void orphanTone() {
        if (shadeScene == null || !Boolean.TRUE.equals(shadeExpanded)) return;
        boolean swap = shadeConfigEnabled() && Tuning.get().textSwap && shadeScene == ShadeTone.lightText();
        int n = 0;
        for (State st : new ArrayList<>(states.values())) {
            View r = st.row.get();
            if (r == null || st.pill || st.toneOpen == shadeOpens || !r.isShown()) continue;
            st.setForeground(swap);
            if (swap) orphans.add(st); else orphans.remove(st);
            n++;
        }
        if (n > 0) Probe.log("TEXT_ORPHAN", "rows=" + n + " swap=" + swap + " generation=" + shadeOpens);
    }
    /** The shade window's last blur info and its host, SystemUI's own colour on it, and the dim applied. */
    private WeakReference<View> panelHost;
    private Object panelInfo;
    private int panelOwnColor, panelApplied;
    private int panelOwnRadius, panelAppliedRadius;
    private Object panelOwnAdjustment, panelAppliedAdjustment;
    private String lastPanelPolicy;
    private boolean panelDim;
    /**
     * The shade window's blur darkened over a light app in the dark theme (debug.oulg.paneldim):
     * the light theme's panel sits at ~235 over a white app and its cards at ~250, so they stand
     * out; the dark theme's sat at ~250 under cards of ~249 and the cards dissolved into it
     * (device screenshots, 26/10). Called for every blur SystemUI sets on the shade window.
     */
    private void panelBlur(View v, Object info) {
        Field f = Reflect.field(info.getClass(), "mBackgroundBlurColor");
        if (f == null) return;
        try {
            int now = f.getInt(info);
            Field radius = Reflect.field(info.getClass(), "mBlurRadius");
            Field gray = Reflect.field(info.getClass(), "mBGColorAdjustment");
            int nativeRadius = radius == null ? -1 : radius.getInt(info);
            Object nativeAdjustment = gray == null ? null : gray.get(info);
            boolean fresh = info != panelInfo;
            if (fresh || now != panelApplied) panelOwnColor = now;
            if (radius != null && (fresh || nativeRadius != panelAppliedRadius)) panelOwnRadius = nativeRadius;
            if (gray != null && (fresh || nativeAdjustment != panelAppliedAdjustment)) panelOwnAdjustment = nativeAdjustment;
            panelHost = new WeakReference<>(v); panelInfo = info;
            Tuning tuning = Tuning.get();
            boolean shade = barState == Eligibility.BAR_SHADE || Boolean.TRUE.equals(shadeExpanded);
            float dim = tuning.panelDim;
            int overlay = shade ? tuning.panelColor : 0;
            int color = overlay == 0 ? panelOwnColor : ShadeTone.overNative(overlay, panelOwnColor);
            panelApplied = panelDim && dim > 0f ? ShadeTone.overNative(Math.round(255f * dim) << 24, color) : color;
            panelAppliedRadius = radius == null ? nativeRadius : shade
                    ? io.github.igorcv88.oneuiliquidglass.glass.PanelBlurPolicy.radius(panelOwnRadius, tuning.panelBlurCap)
                    : panelOwnRadius;
            panelAppliedAdjustment = gray == null || !shade || tuning.panelNativeGray ? panelOwnAdjustment : null;
            if (now != panelApplied) f.setInt(info, panelApplied);
            if (radius != null && nativeRadius != panelAppliedRadius) radius.setInt(info, panelAppliedRadius);
            if (gray != null && nativeAdjustment != panelAppliedAdjustment) gray.set(info, panelAppliedAdjustment);
            if (Probe.trace) {
                String diag = "shade=" + shade + " nativeRadius=" + panelOwnRadius + " appliedRadius=" + panelAppliedRadius
                        + " nativeAdjustment=" + (panelOwnAdjustment == null ? "none" : panelOwnAdjustment.toString())
                        + " appliedAdjustment=" + (panelAppliedAdjustment == null ? "none" : "native")
                        + " nativeColor=" + Integer.toHexString(panelOwnColor) + " appliedColor=" + Integer.toHexString(panelApplied)
                        + " overlay=" + Integer.toHexString(overlay) + " dim=" + dim;
                if (!diag.equals(lastPanelPolicy)) { lastPanelPolicy=diag; Probe.log("PANEL_BLUR_POLICY",diag); }
            }
        } catch (IllegalAccessException | RuntimeException e) { Probe.error("PANEL_DIM_FAILED", e); }
    }

    /** Push a saved shade-window parameter live, without reintroducing the blur on a closing panel. */
    private void refreshPanelBlur() {
        View host=panelHost==null?null:panelHost.get();
        if(host==null||panelInfo==null||!host.isAttachedToWindow()||!Boolean.TRUE.equals(shadeExpanded))return;
        try { View.class.getMethod("semSetBlurInfo",Class.forName("android.view.SemBlurInfo")).invoke(host,panelInfo); }
        catch(ReflectiveOperationException|RuntimeException e){Probe.error("PANEL_BLUR_REFRESH_FAILED",e);}
    }
    /** Turns the panel dim on for this opening's scene, and sets the panel's blur again so it shows now. */
    private void panelDim(boolean on) {
        if (panelDim == on) return;
        panelDim = on;
        Probe.log("PANEL_DIM", "on=" + on + " alpha=" + Tuning.get().panelDim);
        View host = panelHost == null ? null : panelHost.get();
        // Turned off only by the closing shade, whose own blur calls restore the colour: setting the
        // last (fully open) blur again there would flash it back.
        if (!on || host == null || panelInfo == null) return;
        try { View.class.getMethod("semSetBlurInfo", Class.forName("android.view.SemBlurInfo")).invoke(host, panelInfo); }
        catch (ReflectiveOperationException | RuntimeException e) { Probe.error("PANEL_DIM_FAILED", e); }
    }
    private final HeaderToneController headerInk = new HeaderToneController();
    private void headerTone(ToneGrid grid) {
        View pv=panelView==null?null:panelView.get();
        Tuning t=Tuning.get();
        String headerFlag=io.github.igorcv88.oneuiliquidglass.config.ConfigClient.override("headertone");
        String textFlag=io.github.igorcv88.oneuiliquidglass.config.ConfigClient.override("textswap");
        boolean headerOn=headerFlag==null?t.headerTone:!"0".equals(headerFlag);
        boolean textOn=textFlag==null?t.textSwap:!"0".equals(textFlag);
        if(!shadeConfigEnabled()||!headerOn||!textOn){restoreHeader();return;}
        composeHeader(grid);
        if(pv!=null)headerInk.update(pv.getRootView(),grid);
    }
    private void restoreHeader() {
        headerInk.clear();
        shadeHeaderGate.reset(); shadeHeaderLight = null;
        if (quickSettings != null) quickSettings.shadeHeader(null);
    }
    /**
     * The clock and date drawn over the notification shade are One UI 9's Compose header
     * (SecQuickSettingHeader), the same one the QS panel shows: its labels follow this decision,
     * from the top bands of the capture (where the header rests), while only the shade is open.
     */
    private final io.github.igorcv88.oneuiliquidglass.glass.ToneGate shadeHeaderGate =
            new io.github.igorcv88.oneuiliquidglass.glass.ToneGate(CONFIRM_MS, HeaderToneController.DWELL_MS);
    private Boolean shadeHeaderLight;
    private void composeHeader(ToneGrid grid) {
        if (quickSettings == null) return;
        float luma = grid.luma(grid.top, grid.top + 4 * grid.band);
        if (!Float.isFinite(luma) || luma < 0f || luma > 1f) return;
        Tuning t = Tuning.get();
        boolean light = ShadeTone.lightBackdrop(luma, shadeHeaderLight);
        boolean strong = io.github.igorcv88.oneuiliquidglass.glass.ToneGate.strong(luma, (t.styleLo + t.styleHi) * 0.5f);
        if (!shadeHeaderGate.offer(light, android.os.SystemClock.uptimeMillis(), strong)) return;
        if (shadeHeaderLight != null && shadeHeaderLight == light) return;
        shadeHeaderLight = light;
        Probe.log("SHADE_HEADER_TONE", "generation=" + shadeOpens + " luma=" + String.format(java.util.Locale.ROOT, "%.2f", luma)
                + " tone=" + (light ? "dark" : "light") + " strong=" + strong);
        quickSettings.shadeHeader(light);
    }
    /**
     * First frame of a shade opening: the backdrop tone starts over. A pre-open capture younger than
     * PRE_OPEN_MAX_MS is adopted at once; otherwise one is asked for now, before the panel or any
     * card is visible, and applies only to this generation. Cards drawn before it lands keep their
     * theme's native look (no veil, no swap): over a white app the body stays light and only the
     * text tone changes when the capture lands, instead of a light card turning grey.
     */
    private void beginShadeTone(Object controller) {
        stopResample();
        int generation = shadeOpens;
        shadeGrid = null; shadeScene = null; shadeOpenAt = android.os.SystemClock.uptimeMillis(); shadeCards = 0; shadeEarlyDraws = 0;
        Tuning t = Tuning.get();
        boolean adaptive = t.shadeAdaptive && barState != null && barState == Eligibility.BAR_SHADE;
        ToneGrid pre = preGrid;
        preGrid = null;
        long preAge = pre == null ? -1 : shadeOpenAt - pre.takenAt;
        boolean preOk = pre != null && preValid(preGridGesture, pre);
        Probe.log("SHADE_OPEN_BEGIN", "generation=" + generation + " adaptive=" + adaptive + " preAge=" + preAge + " preValid=" + preOk);
        if (!adaptive) return;
        if (preOk) { adoptGrid(pre.forGeneration(generation), "preopen"); return; }
        Object panel = Reflect.read(controller, "mView");
        View anchor = panel instanceof View && ((View) panel).isAttachedToWindow() ? (View) panel : null;
        for (State state : new ArrayList<>(states.values())) {
            if (anchor != null) break;
            View v = state.background.get(); if (v != null && v.isAttachedToWindow()) anchor = v;
        }
        if (anchor == null) { Probe.log("SHADE_PREFETCH", "generation=" + generation + " state=unavailable reason=noView"); return; }
        ShadeTone.prefetch(anchor, generation, grid -> {
            long dt = android.os.SystemClock.uptimeMillis() - shadeOpenAt;
            if (generation != shadeOpens || !Boolean.TRUE.equals(shadeExpanded)) {
                Probe.log("SHADE_PREFETCH", "generation=" + generation + " state=cancel tFromOpen=" + dt);
                return;
            }
            if (grid == null) { Probe.log("SHADE_PREFETCH", "generation=" + generation + " state=unavailable tFromOpen=" + dt); return; }
            // A pre-open capture that landed meanwhile is as good and older-or-equal: keep the first.
            if (shadeGrid != null && shadeGrid.generation == generation) return;
            adoptGrid(grid, "sample");
        });
    }
    private void endShadeTone() {
        if (shadeOpenAt == 0) return;
        Probe.log("SHADE_OPEN_END", "generation=" + shadeOpens + " durationMs=" + (android.os.SystemClock.uptimeMillis() - shadeOpenAt)
                + " cards=" + shadeCards + " earlyDraws=" + shadeEarlyDraws + " sampled=" + (shadeGrid != null)
                + " intercepted=" + Foreground.interceptions());
        stopResample();
        shadeGrid = null; shadeOpenAt = 0;
        restoreHeader();
        for (State st : new ArrayList<>(orphans)) st.setForeground(false);
        orphans.clear();
        panelDim(false);
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
        long started = Perf.start(Perf.SHADE);
        try { applyShade(controller); } finally { Perf.end(Perf.SHADE, started); }
    }
    private void applyShade(Object controller) {
        if (panelView == null || panelView.get() == null) {
            Object pv = Reflect.read(controller, "mView");
            if (pv instanceof View) panelView = new WeakReference<>((View) pv);
        }
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
            if (next) { shadeOpens++; beginShadeTone(controller); }
            else endShadeTone();
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
    /**
     * The card's disappear animation (One UI removes a card, or takes a heads-up away, through
     * startAppearAnimation(false) and ends it with onAppearAnimationFinished): its material is held
     * and its region follows the exit (CardExit). Without the isAppearing argument a removed row
     * counts as leaving. A heads-up animating away into the shade runs no disappear animation and
     * is not affected.
     */
    private void exitEvent(View row, String name, Object[] args) {
        long started = Perf.start(Perf.EXIT);
        try { exit(row, name, args); } finally { Perf.end(Perf.EXIT, started); }
    }
    private void exit(View row, String name, Object[] args) {
        Object background = Reflect.read(row, "mBackgroundNormal");
        if (!(background instanceof View)) return;
        // A cancel on a row with no state yet has nothing to end.
        State s = name.startsWith("cancel") ? states.get(background) : state((View) background, row);
        if (s == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if ("onAppearAnimationFinished".equals(name)) { s.exitEnd(now, "finished"); return; }
        if (name.startsWith("cancel")) { s.exitEnd(now, "cancelled"); return; }
        Boolean appearing = CardExit.appearing(args);
        if (appearing == null) appearing = !Boolean.TRUE.equals(Reflect.bool(row, "isRemoved", "mRemoved"));
        if (appearing) s.exitEnd(now, "appear");
        else s.exitBegin(now, CardExit.appearing(args) != null ? "arg" : "removed");
    }
    private void observeBackground(View bg, String event) {
        View row = findRow(bg);
        if (row != null) {
            if (Probe.trace) {
                try { Probe.log("SHADE_LAYER_UPDATE", layerIdentity(bg, row)
                        + " event=" + event + " native=" + nativeLayer(bg)); }
                catch (RuntimeException | LinkageError e) { Probe.error("SHADE_LAYER_TRACE_FAILED", e); }
            }
            state(bg, row).event(event);
        }
    }

    private String layerIdentity(View bg, View row) {
        Object normal = Reflect.read(row, "mBackgroundNormal");
        Object dimmed = Reflect.read(row, "mBackgroundDimmed");
        return "rowId=" + Integer.toHexString(System.identityHashCode(row))
                + " bgId=" + Integer.toHexString(System.identityHashCode(bg))
                + " role=" + ShadeLayerTrace.role(bg, normal, dimmed)
                + " normalKnown=" + (normal instanceof View)
                + " dimmedKnown=" + (dimmed instanceof View);
    }

    private String nativeLayer(View bg) {
        Object nativeDrawable = Reflect.read(bg, "mBackground");
        return nativeDrawable instanceof Drawable
                ? nativeDrawable.getClass().getSimpleName() + "@" + ((Drawable) nativeDrawable).getAlpha()
                : nativeDrawable == null ? "null" : nativeDrawable.getClass().getSimpleName();
    }

    /** Called after the real onDraw, including layers not intercepted by the glass replacement. */
    private void traceLayerDraw(View bg, String render, boolean failed) {
        if (!Probe.trace || !bg.isShown()) return;
        try { traceLayerDrawSafe(bg, render, failed); }
        catch (RuntimeException | LinkageError e) { Probe.error("SHADE_LAYER_TRACE_FAILED", e); }
    }

    private void traceLayerDrawSafe(View bg, String render, boolean failed) {
        View row = findRow(bg);
        if (row == null) return;
        LayerSample sample = layerSamples.get(bg);
        if (sample == null) { sample = new LayerSample(); layerSamples.put(bg, sample); }
        sample.draws++;
        long order = ++layerDrawOrder;
        long now = android.os.SystemClock.uptimeMillis();
        State state = states.get(bg);
        String signature = "render=" + render + " failed=" + failed
                + " rowAlpha=" + Math.round(row.getAlpha() * 20f)
                + " bgAlpha=" + Math.round(bg.getAlpha() * 20f)
                + " bgVisibility=" + bg.getVisibility()
                + " glassOwned=" + (state != null && state.glass != null)
                + " suspended=" + (state != null && state.stacked)
                + " native=" + nativeLayer(bg);
        if (!ShadeLayerTrace.emit(sample.draws, signature, sample.last, now, sample.loggedAt)) return;
        sample.last = signature;
        sample.loggedAt = now;
        Probe.log("SHADE_LAYER_DRAW", "order=" + order + " uptime=" + now
                + " " + layerIdentity(bg, row) + " " + signature
                + " shadeExpanded=" + shadeExpanded + " barState=" + barState);
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
    /** How often a shown pop-up measures the backdrop under it again (the app may scroll or change). */
    // A stationary heads-up still needs to notice a background app switch promptly. One second
    // missed the white -> dark change even when the outward direction happened immediately.
    private static final long POPUP_MEASURE_MS = 250;
    private static final long SWAP_WALK_MS = 500;
    /** More than CONTEST_DRIFTS drift repairs of one card within CONTEST_WINDOW_MS: a writer is contesting the colour. */
    private static final int CONTEST_DRIFTS = 6;
    private static final long CONTEST_WINDOW_MS = 500;
    /** View descriptions already logged as TEXT_TONE_DRIFT (class and id only). */
    private final Set<String> driftLogged = new java.util.HashSet<>();
    /** How recent a card's last measurement must be to stand for it at the start of a shade opening. */
    private static final long HOLD_MS = 1500;
    /** While a pop-up enters, how often the backdrop under it is measured again. */
    private static final long POPUP_ENTER_MS = 50;
    /** A pop-up's change of side needs a second measurement this much later, and this long since its last change. */
    // Symmetric confirmation in both directions; strong changes still pass immediately.
    private static final long POPUP_CONFIRM_MS = 100, POPUP_DWELL_MS = 220;
    /** How long after the last touch a shown pop-up keeps measuring at POPUP_ENTER_MS. */
    private static final long POPUP_ACTIVE_MS = 1500;
    private static final String SURFACE_SHADE = "shade", SURFACE_POPUP = "popup";
    /** The accumulated alpha of a view and its ancestors. */
    private static float shownAlpha(View v) {
        float a = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) a *= ((View) p).getAlpha();
        return a;
    }
    /** The row's content views; the shown one carries the content alpha of a disappear animation. */
    private static final String[] EXIT_CONTENT = {"mPrivateLayout", "mChildrenContainer", "mPublicLayout"};
    /** EXIT_SIGNAL (which exit signals this firmware has) is logged once per process. */
    private boolean exitSignalLogged;
    private static String fmt(float v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    /** The notification's key (NotificationEntry.getKey), to correlate a card with SystemUI's own logs (trace only). */
    private static String notificationKey(View row) {
        if (row == null) return "null";
        try {
            Object entry = Reflect.call(row, "getEntry");
            Object key = entry == null ? null : Reflect.call(entry, "getKey");
            return key == null ? "unknown" : String.valueOf(key);
        } catch (ReflectiveOperationException | RuntimeException e) { return "unknown"; }
    }
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
        Boolean lastEligible;
        /** Surface and reason of the last DECISION line (reason null: glass). */
        String lastSurface, lastReason;
        /** Last reason the native background was drawn instead of the glass; null while glass draws. */
        String lastFallback;
        Boolean lastHeadsUp;
        /** Heads-up arrivals of this row (false to true), and the arrival popupTone last started from. */
        int popupArrival, popupToneArrival = -1;
        Boolean toneHeadsUp;
        int width = -1, height = -1;
        final float[] lastRadii = new float[8];
        final int[] location = new int[2], lastLocation = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        int tuningGeneration;
        /** A material decision is logged once per published Tuning generation, not each draw. */
        int loggedMaterialGeneration = -1;
        /** The row is scaled into a collapsed group's stack (see onPreDraw). */
        boolean stacked;
        /** The shade opening whose backdrop this card's veil was taken from (shadetone=3). */
        int toneOpen = -1;
        /** Which surface the veil was measured for (SHADE, POPUP) or null, and from what (native, grid, sample). */
        String toneSurface, toneSource;
        /** Uptime of the last pop-up measurement request, and the card's position and alpha then. */
        long popupMeasureAt;
        int popupMeasuredY;
        float popupMeasuredAlpha;
        /** The backdrop read as light at the last measurement (the style's hysteresis state). */
        boolean lightBackdrop;
        /** The style last logged (MATERIAL_STYLE), and the opening whose first visible draw was logged. */
        String loggedStyle;
        int firstVisibleOpen = -1;
        /** Whether this card's text and icons carry the swapped tone, and when its views were last walked. */
        boolean swapped;
        long swapAt;
        /** The views carrying the swapped tone (Foreground.drift checks them every pre-draw). */
        final ArrayList<View> swappedViews = new ArrayList<>();
        /** Uptime of the last measurement applied to this card (grid or sample), 0 before any. */
        long toneAt;
        /**
         * Whether this card already showed a sampled tone in the current surface session, and so
         * whether the next foreground change is a change (animated) or the first decision (at once:
         * the first contrast fix must not fade through low-contrast greys). Cleared by setNative.
         */
        boolean sampledTone, animateSwap;
        /** Poke that makes an entering pop-up draw (and measure) again: see popupTone. */
        final Runnable popupPoke = this::invalidate;
        final int[] toneXy = new int[2];
        /**
         * The card's material from the backdrop behind it. Shade cards: the opening's capture
         * (ToneGrid, possibly taken at the pull-down's first touch) under the card's current position;
         * before it lands, the theme's native look. Pop-ups over an app in LSPosed-only mode: native
         * on the first frame, then a capture under the card every POPUP_ENTER_MS while it enters
         * (fading in or moving) and every POPUP_MEASURE_MS once still. Any other surface carries no
         * pop-up veil and no swapped text.
         */
        /**
         * tone() isolated: a failure in the measurement, the veil or the text swap is logged and
         * leaves the card's glass alone. Called from onPreDraw and material(), an exception used to
         * mark the card failed (PREDRAW_FAILED / DRAW_SWAP_FAILED) and leave it on another material
         * for the rest of the process.
         */
        void safeTone(View v) {
            try { tone(v); }
            catch (RuntimeException | LinkageError e) {
                if (toneErrors++ >= 5) return;
                StringBuilder at = new StringBuilder();
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) at.append(i == 0 ? "" : "<").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
                Probe.log("TONE_FAILED", "error=" + e.getClass().getSimpleName() + " at=" + at);
            }
        }
        int toneErrors;
        /**
         * The notification shelf in the expanded shade ("N more notifications" under a collapsed
         * stack) is not a row and keeps SystemUI's own blur, but sits among the cards: its text
         * follows the shade's scene, so it turns dark with them over a light app instead of staying
         * white (device video, 25/10). Restored when the shade closes or the scene is native.
         */
        void shelfTone() {
            try {
                Tuning t = Tuning.get();
                boolean on = shadeConfigEnabled() && t.textSwap && t.shadeAdaptive && Boolean.TRUE.equals(shadeExpanded) && barState != null
                        && barState == Eligibility.BAR_SHADE && shadeScene != null && shadeScene == ShadeTone.lightText();
                setForeground(on);
            } catch (RuntimeException | LinkageError e) { if (toneErrors++ < 5) Probe.error("SHELF_TONE_FAILED", e); }
        }
        void tone(View v) {
            if (!surfaceEnabled() || !feature("tonemap")) { setNative(); return; }
            Tuning t = Tuning.get();
            if (t.shadeAdaptive && Boolean.TRUE.equals(shadeExpanded) && barState != null && barState == Eligibility.BAR_SHADE) { shadeTone(v); return; }
            // The compositor-lens mode (sfrefract) draws through the Samsung blur: the pop-up's text
            // tone follows the backdrop there too; its veil slot exists only on the live drawable.
            if (t.popupTone && popupSurface() && ((t.live && compBridge != null) || (t.sfRefract && semBridge != null))) { popupTone(v); return; }
            // Unlocked, between surfaces (a pop-up handing over to the shade: heads-up animating away,
            // the shade not yet expanded): hold the material. Restoring here and applying it again a
            // few frames later flashed the text white-dark (TEXT_TONE swap=false, then true 50 ms
            // later, device log 25/10). A held pop-up veil reaches no shade card (own slot).
            if (barState != null && barState == Eligibility.BAR_SHADE) { setForeground(swapped); return; }
            if (SURFACE_POPUP.equals(toneSurface)) {
                if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
                pushToneVeil(0);
                toneSurface = null; toneSource = null;
            }
            setForeground(false);
            setLightStyle(false);
        }
        void shadeTone(View v) {
            boolean first = toneOpen != shadeOpens;
            float luma;
            String source;
            ToneGrid grid = shadeGrid;
            if (grid != null && grid.generation == shadeOpens) {
                v.getLocationOnScreen(toneXy);
                // One luminosity for the whole stack, from the whole screen: a card under a white
                // patch of the wallpaper (a cloud) was veiled darker than the rest (device frame,
                // 26/10). Each card's colour still comes from the live blur behind it.
                // debug.oulg.shadelocal=1 measures each card's own band again.
                luma = Tuning.get().shadeLocal ? grid.luma(toneXy[1], toneXy[1] + v.getHeight()) : grid.whole();
                if (luma < 0f) return;
                source = "grid";
            } else if (first && toneAt > 0 && android.os.SystemClock.uptimeMillis() - toneAt <= HOLD_MS) {
                // Measured moments ago (the pop-up this card was, or a reopened shade): that reading
                // stands until this opening's capture lands, instead of native first and back.
                luma = Float.NaN;
                source = "held";
            } else if (first) {
                luma = Float.NaN;
                source = "native";
            } else {
                if (v.isShown() && shownAlpha(v) > 0.01f) shadeEarlyDraws++;
                firstVisible(v);
                return;
            }
            boolean changedSource = !source.equals(toneSource);
            boolean changedSurface = !SURFACE_SHADE.equals(toneSurface);
            if (first) shadeCards++;
            toneOpen = shadeOpens; toneSurface = SURFACE_SHADE; toneSource = source;
            // The shade slot may hold an older veil than the one the pop-up slot had.
            if (changedSurface) pushToneVeil();
            if (source.equals("native")) setNative(); else if (!Float.isNaN(luma)) setTone(luma, changedSource);
            else setForeground(swapped);
            if (changedSource) logTone("SHADE_TONE_APPLY", v, source, luma, grid != null && source.equals("grid") ? grid.bright(toneXy[1], toneXy[1] + v.getHeight()) : -1f,
                    " generation=" + shadeOpens + " tFromOpen=" + (android.os.SystemClock.uptimeMillis() - shadeOpenAt)
                    + (grid != null && source.equals("grid") ? " age=" + (android.os.SystemClock.uptimeMillis() - grid.takenAt) : ""));
            firstVisible(v);
        }
        /** FIRST_VISIBLE_MATERIAL: the material a card shows on its first visible draw of an opening. */
        void firstVisible(View v) {
            if (firstVisibleOpen == shadeOpens || !v.isShown() || shownAlpha(v) <= 0.01f) return;
            firstVisibleOpen = shadeOpens;
            // Recorded at pre-draw: the material this card's next draw is built with, not proof of
            // which frame reached the display (RenderThread and the compositor follow).
            logTone("FIRST_VISIBLE_MATERIAL", v, String.valueOf(toneSource), Float.NaN, -1f,
                    " generation=" + shadeOpens + " tFromOpen=" + (android.os.SystemClock.uptimeMillis() - shadeOpenAt) + " at=preDraw");
        }
        void popupTone(View v) {
            long now = android.os.SystemClock.uptimeMillis();
            // A new heads-up arrival on this row starts over: its tone is measured afresh.
            if (!SURFACE_POPUP.equals(toneSurface) || popupToneArrival != popupArrival) {
                popupToneArrival = popupArrival;
                toneSurface = SURFACE_POPUP; popupMeasureAt = 0; popupMeasuredAlpha = 0f; popupScene = null; popupGate.reset();
                // The pop-up slot is empty or stale: write the current veil before any step from it.
                pushToneVeil();
                toneSource = "native";
                setNative();
                logTone("POPUP_CONTRAST", v, toneSource, Float.NaN, -1f, "");
            } else setForeground(swapped);
            // Stabilised (popuplock): after the first measurement of this arrival the pop-up keeps
            // its look until a later measurement reads the other side of the classifier and the next
            // one, POPUP_CONFIRM_MS on, confirms it (popupGate). Applying every measurement switched
            // between looks over moving content (device, 26/10); holding the first one for good left
            // a pop-up dark-on-dark after the app behind it turned black.
            if (!v.isShown()) return;
            v.getLocationOnScreen(toneXy);
            float alpha = shownAlpha(v);
            // While it enters (fading in, sliding down) the region under it is not yet the final one:
            // measure again every POPUP_ENTER_MS until it is opaque and still, then every second.
            boolean entering = popupMeasuredAlpha < 0.99f || Math.abs(toneXy[1] - popupMeasuredY) > 8;
            // A touch in the last POPUP_ACTIVE_MS may have changed the app behind (home, an app
            // launch): measure as often as while entering. Still for 1 s, the text stayed white on a
            // white app for ~270 ms after a launch (device video, 25/10).
            boolean active = now - BackdropMotion.lastActive() < POPUP_ACTIVE_MS || BackdropEvents.burst(now);
            long interval = entering || active ? POPUP_ENTER_MS : POPUP_MEASURE_MS;
            // A candidate change waits for its confirming measurement, which is taken early.
            if (popupGate.pending()) interval = Math.min(interval, Math.max(POPUP_ENTER_MS, popupGate.confirmIn(now)));
            if (now - popupMeasureAt < interval) {
                // Not due yet (the interval grew as the pop-up settled): keep the timer armed for the
                // rest of it, or the loop would stop once no other draw comes.
                v.removeCallbacks(popupPoke);
                v.postDelayed(popupPoke, interval - (now - popupMeasureAt));
                return;
            }
            popupMeasureAt = now; popupMeasuredY = toneXy[1]; popupMeasuredAlpha = alpha;
            // The entrance animates RenderNode properties without redrawing the card: without a poke
            // the next measurement waited for an unrelated draw (1.2 s on the device). One redraw per
            // interval keeps the cadence; still pop-ups are poked once a second.
            v.removeCallbacks(popupPoke);
            v.postDelayed(popupPoke, interval);
            long requested = now;
            int arrival = popupArrival;
            ShadeTone.measure(v, luma -> {
                if (!SURFACE_POPUP.equals(toneSurface) || arrival != popupArrival) return;
                boolean firstSample = !"sample".equals(toneSource);
                if (!firstSample && Tuning.get().popupLock) {
                    // Only a confirmed change of side moves a pop-up that already has its look;
                    // readings on the same side keep the veil it has (no creep over moving content).
                    long at = android.os.SystemClock.uptimeMillis();
                    boolean light = ShadeTone.lightBackdrop((float) luma, popupScene);
                    boolean pendingBefore = popupGate.pending();
                    Tuning tt = Tuning.get();
                    boolean strong = io.github.igorcv88.oneuiliquidglass.glass.ToneGate.strong((float) luma, (tt.styleLo + tt.styleHi) * 0.5f);
                    boolean commit = popupGate.offer(light, at, strong) && popupScene != null && light != popupScene;
                    if (commit || popupGate.pending() != pendingBefore)
                        logTone("POPUP_CONTRAST_GATE", v, "sample", (float) luma, -1f, " reading=" + (light ? "light" : "dark")
                                + " decided=" + (popupScene == null ? "none" : popupScene ? "light" : "dark") + " commit=" + commit + " strong=" + strong
                                + " pending=" + popupGate.pending() + " sampleAgeMs=" + (at - requested));
                    if (!commit) { if (popupGate.pending()) { v.removeCallbacks(popupPoke); v.postDelayed(popupPoke, POPUP_CONFIRM_MS); } return; }
                } else if (firstSample) popupGate.offer(ShadeTone.lightBackdrop((float) luma, popupScene), android.os.SystemClock.uptimeMillis());
                int before = measuredVeil;
                boolean wasSwapped = swapped;
                toneSource = "sample";
                setTone((float) luma, firstSample);
                if (firstSample || before != measuredVeil || wasSwapped != swapped) logTone("POPUP_CONTRAST", v, "sample", (float) luma, -1f,
                        " age=" + (android.os.SystemClock.uptimeMillis() - requested) + " measuredAlpha=" + String.format(java.util.Locale.ROOT, "%.2f", alpha));
            });
        }
        void logTone(String event, View v, String source, float luma, float bright, String extra) {
            Probe.log(event, "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " source=" + source
                    + " luma=" + (Float.isNaN(luma) ? "na" : String.format(java.util.Locale.ROOT, "%.2f", luma))
                    + (bright >= 0f ? " lumaP90=" + String.format(java.util.Locale.ROOT, "%.2f", bright) : "")
                    + " style=" + (swapped ? (lightBackdrop ? "light" : "dark") : "native") + " veil=" + Integer.toHexString(measuredVeil)
                    + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", shownAlpha(v)) + " shown=" + v.isShown() + extra);
        }
        /** The theme's own look: no veil, no swapped text; the hysteresis starts on the theme's side. */
        void setNative() {
            sampledTone = false; animateSwap = false;
            darkNativeFrost = false;
            if (glass != null) glass.setSharedDarkFrost(false);
            lightBackdrop = !ShadeTone.lightText();
            // At once, never ramped: a ramp would replay the previous opening's (or surface's) veil
            // for VEIL_RAMP_MS on a card that is starting over. Its pending text swap goes with it.
            pendingSwap = null; pendingLightStyle = null;
            if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
            setForeground(false);
            setLightStyle(false);
            if (measuredVeil != 0) { measuredVeil = 0; pushToneVeil(); invalidate(); }
            styleChanged("native");
        }
        /**
         * Moves the card to the material for {@code luma}. A small veil step of one colour (the card
         * sliding over the next band) applies directly, text included; any other change goes
         * through applyMeasuredVeil, which ramps between the two region colours on a visible card
         * (from the native tint when there was no veil) and switches at once on a hidden one. When
         * the text tone changes with it, the swap waits for the ramp's midpoint, where the glass is
         * between the two looks.
         */
        void setTone(float luma, boolean transition) {
            boolean shade = SURFACE_SHADE.equals(toneSurface);
            io.github.igorcv88.oneuiliquidglass.glass.Material m = ShadeTone.materialFor(luma, shade ? shadeScene : popupScene, shade);
            // The native dark shade does not swap text, and therefore never selected DARK_GLASS.
            // Use the per-card veil for increases above the approved default; unlike the native
            // Samsung tint, this survives the global semalpha=0 transparency override.
            int darkNativeExtra = shade && !m.swap && !m.light && m.veil == 0
                    ? io.github.igorcv88.oneuiliquidglass.glass.MaterialMath.shadeDarkNativeExtra(Tuning.get().shadeDarkGlass) : 0;
            darkNativeFrost = darkNativeExtra != 0;
            if (glass != null) glass.setSharedDarkFrost(darkNativeFrost);
            if (shade && loggedMaterialGeneration != Tuning.generation) {
                loggedMaterialGeneration = Tuning.generation;
                View card = background.get();
                Probe.log("SHADE_MATERIAL_DECISION", "viewId="
                        + Integer.toHexString(card == null ? 0 : System.identityHashCode(card))
                        + " generation=" + loggedMaterialGeneration
                        + " revision=" + io.github.igorcv88.oneuiliquidglass.config.ConfigClient.revision()
                        + " style=" + m.style() + " veil=" + Integer.toHexString(m.veil)
                        + " shadelightglass=" + Tuning.get().shadeLightGlass
                        + " shadedarkglass=" + Tuning.get().shadeDarkGlass
                        + " darkNativeExtra=" + Integer.toHexString(darkNativeExtra)
                        + " changed=" + (m.veil != measuredVeil));
            }
            lightBackdrop = m.light;
            if (!shade) popupScene = m.light;
            toneAt = android.os.SystemClock.uptimeMillis();
            animateSwap = sampledTone;
            sampledTone = true;
            styleChanged(m.style());
            // A glass style replaces the tint; in the shade the native side's legibility veil darkens
            // it instead (the pop-up's look is left as approved, 26/10).
            int veil = m.swap || !shade ? m.veil : ShadeTone.overNative(m.veil, nativeTint);
            if (darkNativeExtra != 0) veil = darkNativeExtra;
            boolean swapLight = m.swap && m.light;
            int da = Math.abs((veil >>> 24) - (measuredVeil >>> 24));
            boolean sameColour = veil == 0 || measuredVeil == 0 || (veil & 0x00ffffff) == (measuredVeil & 0x00ffffff);
            if (veil == measuredVeil || (!transition && sameColour && da < 4 && m.swap == swapped)) {
                if (pendingSwap == null && pendingLightStyle == null) { setForeground(m.swap); setLightStyle(swapLight); }
                return;
            }
            // A ramp still running ends here (applying its own pending swap) before this one starts.
            if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
            if (!transition && sameColour && da <= 24 && m.swap == swapped) {
                measuredVeil = veil; pushToneVeil(); invalidate();
                setForeground(m.swap); setLightStyle(swapLight);
                return;
            }
            if (m.swap != swapped) { pendingSwap = m.swap; pendingLight = swapLight; }
            else { setForeground(m.swap); setLightStyle(swapLight); }
            boolean ramped = applyMeasuredVeil(veil);
            // The text moves with the start of the veil, not its midpoint: a decided change of side
            // shows at once (the ramp's midpoint held white text over a white app ~100 ms longer).
            // The glass's own light style (bright outline, stronger thickness shadow) still waits
            // for the midpoint, as before #80: switched at the ramp's start it jumped ahead of the veil.
            boolean text = pendingSwap != null;
            flushPendingSwap();
            if (SURFACE_POPUP.equals(toneSurface)) {
                View pv = background.get();
                Probe.log("POPUP_TRANSITION", "viewId=" + Integer.toHexString(pv == null ? 0 : System.identityHashCode(pv))
                        + " first=" + !animateSwap + " veilRamp=" + ramped + " rampMs=" + (ramped ? VEIL_RAMP_MS : 0)
                        + " text=" + (text ? (animateSwap ? "animated" : "immediate") : "unchanged")
                        + " lightStyle=" + (pendingLightStyle == null ? "now" : "midpoint")
                        + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", pv == null ? 0f : shownAlpha(pv)));
            }
        }
        /** The glass light style a running ramp applies at its midpoint (null: none pending). */
        Boolean pendingLightStyle;
        void flushLightStyle() {
            if (pendingLightStyle == null) return;
            boolean l = pendingLightStyle;
            pendingLightStyle = null;
            setLightStyle(l);
        }
        /** The text tone a running ramp applies at its midpoint (null: none pending). */
        Boolean pendingSwap;
        boolean pendingLight;
        /** The pop-up's scene: its previous reading of the same backdrop; null at the start of a pop-up. */
        Boolean popupScene;
        /** Confirms a pop-up's change of side before its look moves (see popupTone). */
        final io.github.igorcv88.oneuiliquidglass.glass.ToneGate popupGate =
                new io.github.igorcv88.oneuiliquidglass.glass.ToneGate(POPUP_CONFIRM_MS, POPUP_DWELL_MS);
        /** The card's native region colour (what a veil of 0 shows), for the ramp's endpoints. */
        int nativeTint;
        void flushPendingSwap() {
            // No glass check: material() applies a tone before assigning glass, and release() and
            // setNative() already drop the pending swap and the ramp that would flush it.
            if (pendingSwap == null) return;
            boolean s = pendingSwap, l = pendingLight;
            pendingSwap = null;
            setForeground(s);
            if (veilRamp != null) pendingLightStyle = l; else { pendingLightStyle = null; setLightStyle(l); }
        }
        void styleChanged(String style) {
            if (style.equals(loggedStyle)) return;
            String from = loggedStyle;
            loggedStyle = style;
            View v = background.get();
            if (from == null || v == null) return;
            Probe.log("MATERIAL_STYLE", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " surface=" + toneSurface
                    + " from=" + from + " to=" + style + " source=" + toneSource + " alpha=" + String.format(java.util.Locale.ROOT, "%.2f", shownAlpha(v))
                    + (SURFACE_SHADE.equals(toneSurface) ? " generation=" + shadeOpens + " tFromOpen=" + (android.os.SystemClock.uptimeMillis() - shadeOpenAt) : ""));
        }
        /** Swapped text and icon tone; the views are walked again every SWAP_WALK_MS while it is on (new inflations). */
        void setForeground(boolean swap) {
            long now = android.os.SystemClock.uptimeMillis();
            String reason = swap == swapped ? null : swap ? "style" : "restore";
            // A child row that just got its own glass may still carry its group's swapped text
            // (Foreground.ownsTone): restore it once, or it kept the group's tone on its own glass.
            if (reason == null && !swap && inherited) reason = "inherited";
            inherited = false;
            if (reason == null && swap) {
                // Swapped already: walk again only if SystemUI changed a colour back (rebind) or
                // replaced the views; the periodic walk stays as a backstop for new views.
                reason = Foreground.drift(swappedViews);
                if (reason != null && !reason.equals("detached")) {
                    String d = Foreground.describe(Foreground.lastDrift);
                    if (driftLogged.size() < 40 && driftLogged.add(d)) Probe.log("TEXT_TONE_DRIFT", "rowId=" + Integer.toHexString(System.identityHashCode(row.get())) + " " + d);
                    // Repaired every frame means a writer the colour guard does not see: stop
                    // fighting it for CONTEST_MS instead of a walk per frame.
                    if (now - driftWindowAt > CONTEST_WINDOW_MS) { driftWindowAt = now; driftCount = 0; }
                    if (++driftCount > CONTEST_DRIFTS) {
                        if (driftCount == CONTEST_DRIFTS + 1) Probe.log("TEXT_CONTESTED", "rowId=" + Integer.toHexString(System.identityHashCode(row.get())) + " " + d);
                        reason = null;
                    }
                }
                if (reason == null && now - swapAt >= SWAP_WALK_MS) reason = "walk";
            }
            if (reason == null) return;
            long sinceSwap = now - swapAt;
            swapAt = now;
            try {
                // A tone change on a card the user is looking at moves its text over a short
                // transition; the first tone of a card and a hidden card change at once.
                View shown = row.get();
                boolean animate = ("style".equals(reason) || "restore".equals(reason)) && shown != null
                        && shown.isShown() && shownAlpha(shown) > 0.01f && animateSwap;
                int changed = Foreground.apply(shown, swap, ShadeTone.lightText(), swappedViews, animate);
                // Recorded only once the walk went through: a failed restore is retried.
                swapped = swap;
                if (changed > 0 && (Probe.trace || animate || reason.equals("rebind") || reason.equals("detached"))) {
                    Probe.log("TEXT_REAPPLY", "rowId=" + Integer.toHexString(System.identityHashCode(row.get())) + " reason=" + reason
                            + " swap=" + swap + " views=" + changed + " animated=" + animate + " sinceLastWalk=" + sinceSwap + " surface=" + toneSurface
                            + " style=" + (swap ? (lightBackdrop ? "light" : "dark") : "native")
                            + (SURFACE_SHADE.equals(toneSurface) ? " generation=" + shadeOpens : ""));
                }
            } catch (RuntimeException e) { Probe.error("TEXT_TONE_FAILED", e); }
        }
        int driftCount;
        long driftWindowAt;
        /** Set when this row's glass is built: its text may carry its group's tone until the next setForeground. */
        boolean inherited;
        /** Light glass: the edge's thickness shadow is drawn stronger (debug.oulg.lightedge) to define the card on white. */
        void setLightStyle(boolean on) {
            lightStyle = on;
            if (glass != null) { glass.setLightStyle(on, Tuning.get().lightEdge); glass.setOutline(outline()); }
        }
        /**
         * A shade or pop-up card whose glass is light (the light theme's own card, or light glass
         * over a white app in the dark theme) gets a brighter edge, light bent round its outer
         * contour (debug.oulg.lightoutline): white on white, the card's end was barely visible
         * (device screenshots, 26/10). The lockscreen keeps its look.
         */
        float outline() {
            if (toneSurface == null) return 0f;
            boolean light = lightStyle || (!swapped && !ShadeTone.lightText());
            return light ? Tuning.get().lightOutline : 0f;
        }
        boolean lightStyle;
        /** The veil measured for this card at the current shade opening (0: none needed). */
        int measuredVeil;
        boolean darkNativeFrost;
        /** The drawable path could not be built for this card: it keeps the Samsung blur. */
        boolean drawableFailed;
        void pushToneVeil() { pushToneVeil(measuredVeil); }
        void pushToneVeil(int veil) { pushToneVeil(veil, false); }
        void pushToneVeil(int veil, boolean ramping) {
            try {
                // A pop-up's veil has its own slot: the shade veil must not reach a lens card.
                if (SURFACE_POPUP.equals(toneSurface)) { if (compBridge != null) compBridge.setPopupVeil(veil); return; }
                // SharedBackdrop has no blur bridge: paint the light material over the panel's
                // existing blur. During a ramp the body follows every frame, even while the edge
                // light style is still awaiting its midpoint switch.
                if (glass != null && glassKind == Backdrop.Kind.SHARED) glass.setSharedVeil(veil, ramping);
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
        /** Returns whether a visible ramp started (false: applied at once). */
        boolean applyMeasuredVeil(int veil) {
            int from = measuredVeil;
            measuredVeil = veil;
            if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
            View v = background.get();
            // SharedBackdrop draws the body's native fill, not the Samsung region tint. Using
            // nativeTint here caused a visible jump as the ramp entered or left the native side.
            int rampBase = nativeTint;
            if (glassKind == Backdrop.Kind.SHARED && v != null) {
                boolean night = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
                rampBase = night ? spec.shadeDarkFill : spec.shadeLightFill;
            }
            int[] ends = io.github.igorcv88.oneuiliquidglass.glass.VeilRamp.ends(from, veil, rampBase);
            if (Probe.trace && glassKind == Backdrop.Kind.SHARED && ends != null)
                Probe.log("SHADE_SHARED_RAMP_ENDPOINTS", "viewId=" + Integer.toHexString(System.identityHashCode(v))
                        + " from=" + Integer.toHexString(ends[0]) + " to=" + Integer.toHexString(ends[1])
                        + " nativeBody=" + Integer.toHexString(rampBase));
            // A card still fading in (the shade's first frames) takes the new veil at once: a ramp
            // would only replay the old veil under the fade.
            if (ends == null || v == null || !v.isShown() || shownAlpha(v) < 0.5f
                    || (compBridge == null && semBridge == null && glassKind != Backdrop.Kind.SHARED)) {
                pushToneVeil(); invalidate(); flushLightStyle(); return false;
            }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofArgb(ends[0], ends[1]);
            a.setDuration(VEIL_RAMP_MS);
            a.addUpdateListener(an -> {
                pushToneVeil((Integer) an.getAnimatedValue(), true);
                if (an.getAnimatedFraction() >= 0.5f) { flushPendingSwap(); flushLightStyle(); }
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator an) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    // ValueAnimator also invokes onEnd after cancel. Finishing an obsolete ramp
                    // snapped the old colour back over a newer white/dark transition.
                    if (!cancelled) { pushToneVeil(); flushPendingSwap(); flushLightStyle(); invalidate(); }
                }
            });
            veilRamp = a;
            a.start();
            return true;
        }
        // Hiding the region until the new measurement arrived made every opening pop in late; the
        // card keeps the veil of the previous opening meanwhile (measuredVeil persists), which is
        // already right when the backdrop has not changed.
        /** Last frame the card was scaled or faded (uptime ms); see onPreDraw. */
        long lastMotion = Long.MIN_VALUE / 2;
        /** Redraws once the card has been still for SETTLE_MS, so the tag comes back. */
        final Runnable settle = this::settled;
        boolean settlePosted;
        void settled() {
            settlePosted = false;
            View v = background.get();
            if (v == null) return;
            long left = SETTLE_MS - (android.os.SystemClock.uptimeMillis() - lastMotion);
            if (left > 0) { settlePosted = true; v.postDelayed(settle, left + 20); }
            else v.invalidate();
        }
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
        /** The card's exit and detach state: see CardExit. */
        final CardExit exit = new CardExit();
        /**
         * The window the glass was built in (its token, one per ViewRootImpl, whose blur regions the
         * material's drawables belong to): a held material survives a re-attach only there.
         */
        WeakReference<Object> glassWindow;
        /** Releases a material held over a detach unless the card came back first (CardExit.detach). */
        final Runnable heldRelease = this::releaseHeld;
        /** The current exit, for EXIT_END: frames drawn, and when the content and the region went. */
        int exitFrames;
        long exitContentOff = -1, exitRegionOff = -1;
        String lastExitTrace;
        /** The kind and source a leaving card's material was last held against (MATERIAL_HELD once per change). */
        String heldFor;
        void exitBegin(long now, String signal) {
            if (!exit.begin(now)) return;
            exitFrames = 0; exitContentOff = -1; exitRegionOff = -1; lastExitTrace = null;
            View v = background.get(), r = row.get();
            Probe.log("EXIT_BEGIN", "viewId=" + (v == null ? "null" : Integer.toHexString(System.identityHashCode(v)))
                    + " rowId=" + (r == null ? "null" : Integer.toHexString(System.identityHashCode(r)))
                    // The key names the app: only in the opt-in trace (physical-test.md: no keys by default).
                    + (Probe.trace ? " key=" + notificationKey(r) : "")
                    + " signal=" + signal + " headsUp=" + (r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"))
                    + " pinned=" + (r == null ? null : Reflect.bool(r, "isPinned", "mIsPinned"))
                    + " animatingAway=" + (r == null ? null : Reflect.bool(r, "isHeadsUpAnimatingAway", "mHeadsUpAnimatingAway"))
                    + " glass=" + (glass == null ? "none" : glassKind + "/" + glassSource) + " shadeExpanded=" + shadeExpanded + " barState=" + barState);
            invalidate();
        }
        void exitEnd(long now, String why) {
            long took = exit.since(now);
            if (!exit.end()) return;
            View v = background.get();
            Probe.log("EXIT_END", "viewId=" + (v == null ? "null" : Integer.toHexString(System.identityHashCode(v))) + " reason=" + why
                    + " durationMs=" + took + " frames=" + exitFrames + " contentOffMs=" + exitContentOff + " regionOffMs=" + exitRegionOff
                    + " glass=" + (glass != null) + " shown=" + (v != null && v.isShown()));
            invalidate();
        }
        /** The deferred release of a material held over a detach (posted by onViewDetachedFromWindow). */
        void releaseHeld() {
            View v = background.get();
            if (!exit.deferredRelease(v != null && v.isAttachedToWindow())) return;
            release();
            // The shelf keeps its swapped text through release(); a detached shelf gives it back.
            if (pill) setForeground(false);
        }
        /**
         * The exit's own alpha for this card now: the alpha of the row's shown content views and the
         * appear fraction, whichever the firmware has (CardExit.exitAlpha). Read only while leaving.
         */
        float exitAlpha(View r) {
            float content = Float.NaN;
            for (String name : EXIT_CONTENT) {
                Object c = Reflect.read(r, name);
                if (c instanceof View && ((View) c).getVisibility() == View.VISIBLE)
                    content = Float.isNaN(content) ? ((View) c).getAlpha() : Math.max(content, ((View) c).getAlpha());
            }
            Object f = Reflect.read(r, "mAppearAnimationFraction");
            float fraction = f instanceof Float ? (Float) f : Float.NaN;
            if (!exitSignalLogged) {
                exitSignalLogged = true;
                Probe.log("EXIT_SIGNAL", "content=" + !Float.isNaN(content) + " fraction=" + (f instanceof Float) + " row=" + r.getClass().getName());
            }
            return CardExit.exitAlpha(content, fraction);
        }
        /** Per-frame state of a leaving card (debug.oulg.trace only), logged when it changes. */
        void exitTrace(View v, View r, float ancestors, float exitAlpha, String region) {
            if (!Probe.trace) return;
            Object f = Reflect.read(r, "mAppearAnimationFraction");
            String line = "dt=" + exit.since(android.os.SystemClock.uptimeMillis()) + " a=" + fmt(ancestors) + " exit=" + fmt(exitAlpha)
                    + " fraction=" + (f instanceof Float ? fmt((Float) f) : "na") + " rowTy=" + Math.round(r.getTranslationY())
                    + " rowTx=" + Math.round(r.getTranslationX()) + " shown=" + v.isShown()
                    + " headsUp=" + Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp")
                    + " animatingAway=" + Reflect.bool(r, "isHeadsUpAnimatingAway", "mHeadsUpAnimatingAway")
                    + " glass=" + (glass == null ? "none" : String.valueOf(glassKind)) + " region=" + region;
            if (line.equals(lastExitTrace)) return;
            lastExitTrace = line;
            Probe.log("EXIT_FRAME", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " " + line);
        }
        State(View background, View row) {
            this.background = new WeakReference<>(background); this.row = new WeakReference<>(row);
            pill = rowClass == null || !rowClass.isInstance(row);
        }
        void invalidate() { View v = background.get(); if (v != null) v.invalidate(); }
        @Override public void onViewAttachedToWindow(View view) {
            long started = Perf.start(Perf.ATTACH);
            try { attached(view); } finally { Perf.end(Perf.ATTACH, started); }
        }
        private void attached(View view) {
            if (observer != null) return;
            if (exit.held()) {
                main.removeCallbacks(heldRelease);
                // Back in the window its material was built for (the stack re-adding a leaving row
                // as a transient view): the glass and its region carry on. Another window: the
                // region belongs to the old one, released now and built again on the next draw.
                Object then = glassWindow == null ? null : glassWindow.get(), now = view.getWindowToken();
                boolean keep = exit.attach(then != null && then == now);
                Perf.count(keep ? Perf.MATERIAL_KEPT : Perf.MATERIAL_REBUILT);
                Probe.log("MATERIAL_REATTACH", "viewId=" + Integer.toHexString(System.identityHashCode(view)) + " kept=" + keep
                        + (keep ? "" : " reason=" + (glass == null ? "noGlass" : then == null ? "noWindowThen" : now == null ? "noWindowNow" : "otherWindow"))
                        + " leaving=" + exit.leaving(android.os.SystemClock.uptimeMillis()) + " glass=" + (glass != null));
                if (!keep) release();
            }
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
            long started = Perf.start(Perf.DETACH);
            try { detached(view); } finally { Perf.end(Perf.DETACH, started); }
        }
        private void detached(View view) {
            // A row leaving the stack is removed and, in the same message, added back as a transient
            // view for its disappear animation. Released here, its glass came back a frame later
            // and its region two frames later at full alpha (the flash, device log 27/10): the
            // material is held until this message is done, and released then unless re-attached.
            if (exit.detach(glass != null)) { main.removeCallbacks(heldRelease); main.post(heldRelease); }
            else {
                release();
                // The shelf keeps its swapped text through release(); a detached shelf gives it back.
                if (pill) setForeground(false);
            }
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            if (wm != null && blurListener != null) {
                try { wm.removeCrossWindowBlurEnabledListener(blurListener); }
                catch (RuntimeException e) { Probe.error("BLUR_LISTENER_REMOVE_FAILED", e); }
            }
            wm = null; blurListener = null; lastEligible = null;
            Probe.view("DETACH", view);
        }
        @Override public boolean onPreDraw() {
            long started = Perf.start(Perf.PREDRAW);
            try {
                View v = background.get(); if (v == null) return true;
                // Glass off (the app's switch): a card with nothing of the module's left on it (no
                // glass, no swapped text, no native blur owed, no held material) skips the per-frame
                // reflection, eligibility and tone work. The first pre-draw after a detach or a
                // config change runs in full (lastEligible), and turning the glass on invalidates
                // every card (resolveConfig), which then takes the full path again.
                if (minimal()) return true;
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
                    // A leaving card (CardExit) fades its content and folds its outline, not its view
                    // alpha: its region takes the exit's own alpha too, or it stayed whole and slid
                    // away as an empty frosted card (the ghost of a removal, device log 27/10).
                    long now = android.os.SystemClock.uptimeMillis();
                    boolean leaving = exit.leaving(now) && sr != null;
                    float exitAlpha = leaving ? exitAlpha(sr) : 1f;
                    float shown = CardExit.shown(a, leaving, exitAlpha);
                    // In the unlocked shade a partly transparent region turned whiter, not fainter (a
                    // white pill as the card faded): there it is whole or gone (shadefadecut).
                    float cut = barState != null && barState == Eligibility.BAR_SHADE ? Tuning.get().shadeFadeCut : 0f;
                    float fade = CardExit.drawableFade(shown, cut);
                    // The Samsung region does not take its drawable's alpha: a leaving card's is
                    // whole or gone even where the shade has no cut.
                    float semCut = CardExit.samsungCut(cut, leaving);
                    // The tag cannot survive a scaled region; with the finger nearly still the scale
                    // wobbled between 1 and 0.999 and the tag switched every frame. Once the card
                    // scales or fades it stays untagged until it has been still for SETTLE_MS.
                    if (Math.abs(sx - 1f) > 0.001f || Math.abs(sy - 1f) > 0.001f || a < 0.999f) {
                        lastMotion = now;
                        // One pending redraw, moved forward when it fires (settled): a remove and a
                        // post on the main queue for every moving card on every frame cost more.
                        if (!settlePosted) { settlePosted = true; v.postDelayed(settle, SETTLE_MS + 20); }
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
                        semBridge.setFadeCut(semCut > 0f && shown < semCut);
                        // Posted, the region would leave a frame after the content crossed the cut;
                        // pre-draw runs before this frame's draw, so a leaving card's goes now.
                        if (leaving) semBridge.flush();
                        if (semBridge.setRegionFade(semCut > 0f ? 1f : shown)) v.invalidate();
                    }
                    if (leaving) {
                        exitFrames++;
                        long dt = exit.since(now);
                        if (exitAlpha <= 0.01f && exitContentOff < 0) exitContentOff = dt;
                        boolean regionOff = compBridge != null ? stacked || fade <= 0.004f : semBridge.regionOff();
                        if (regionOff && exitRegionOff < 0) exitRegionOff = dt;
                        exitTrace(v, sr, a, exitAlpha, compBridge != null ? "drawable@" + fmt(stacked ? 0f : fade) : "samsung:" + semBridge.regionState());
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
                boolean eligible = eligible();
                // Only a card that draws glass this frame takes a veil or swapped text: with the module
                // disabled, or a card going back to its native background (ineligible, failed), its
                // text is left as SystemUI drew it.
                View r = row.get();
                Boolean headsUp = r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp");
                // Counted before the tone, so a new arrival's first frame is measured, not the last one's tone.
                if (Boolean.TRUE.equals(headsUp) && !Boolean.TRUE.equals(toneHeadsUp)) popupArrival++;
                toneHeadsUp = headsUp;
                if (!pill) {
                    if (Eligibility.textTone(enabled, glass != null, glass != null && glass.failed(), eligible)) safeTone(v);
                    else if (Tuning.get().orphanDraw && surfaceEnabled() && feature("tonemap") && enabled && Boolean.TRUE.equals(shadeExpanded) && shadeScene != null && toneOpen != shadeOpens
                            && barState != null && barState == Eligibility.BAR_SHADE) {
                        // A row with no card of its own (a minimised group's) takes the scene's tone at
                        // its own first draw: orphanTone only reaches rows shown when the capture
                        // lands, and these appeared later and stayed white until its 300 ms pass.
                        boolean swap = Tuning.get().textSwap && shadeScene == ShadeTone.lightText();
                        setForeground(swap);
                        if (swap) orphans.add(this); else orphans.remove(this);
                    }
                    // A row drawn inside its group's glass (orphanTone) keeps the scene's tone: this
                    // restore undid it at the next traversal, and round 43's walk from the group too.
                    else setForeground(feature("tonemap") && surfaceEnabled() && orphans.contains(this) && enabled && Boolean.TRUE.equals(shadeExpanded) && shadeScene != null
                            && shadeScene == ShadeTone.lightText());
                } else shelfTone();
                String reason = reason();
                if (headsUp != null && !headsUp.equals(lastHeadsUp)) {
                    lastHeadsUp = headsUp;
                    Probe.log("HEADSUP", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " headsUp=" + headsUp
                            + " pinned=" + Reflect.bool(r, "isPinned", "mIsPinned") + " shown=" + v.isShown()
                            + " nativeBlur=" + Reflect.bool(v, "isBlurEnabled", "mBlurEnabled"));
                }
                String surface = Eligibility.surface(headsUp, r == null ? null : Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"), shadeExpanded);
                // Compared field by field: a concatenated key was a new string per card per frame.
                if (!surface.equals(lastSurface) || !java.util.Objects.equals(reason, lastReason)) {
                    lastSurface = surface; lastReason = reason;
                    Probe.log("DECISION", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " surface=" + surface
                            + (reason == null ? " glass=true backdrop=" + kind() : " glass=false reason=" + reason));
                }
                if (tuningGeneration != Tuning.generation) {
                    // A debug.oulg.* knob changed: redraw so the material (and Samsung blur) rebuilds now.
                    tuningGeneration = Tuning.generation; v.invalidate();
                }
                if (glassKind == Backdrop.Kind.SAMPLED || (glass != null && glass.hasLens())) {
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
        /** Whether this card is in the disabled state with nothing to restore (see onPreDraw). */
        boolean minimal() {
            return !enabled && !Probe.trace && glass == null && !swapped && !nativePending && !exit.held()
                    && veilRamp == null && Boolean.FALSE.equals(lastEligible);
        }
        String configSurface() { return pill || !popupSurface() && lensSurface() ? "keyguard" : popupSurface() ? "headsup" : "shade"; }
        boolean surfaceEnabled() { return io.github.igorcv88.oneuiliquidglass.config.ConfigClient.flag("surface." + configSurface() + ".enabled"); }
        boolean feature(String name) { return io.github.igorcv88.oneuiliquidglass.config.ConfigClient.flag("surface." + configSurface() + ".feature." + name + ".enabled"); }
        boolean eligible() {
            View v = background.get(), r = row.get();
            if (v == null || r == null || failed || !drawHook || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            if (pill && !pillSurface()) return false;
            return Eligibility.glass(enabled && surfaceEnabled(), v.isAttachedToWindow(), v.isHardwareAccelerated());
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
            if (r == null || v == null || sharedBackdrop() || !feature("lens")) return false;
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
            long started = Perf.start(Perf.EVENT);
            try { onEvent(event); } finally { Perf.end(Perf.EVENT, started); }
        }
        private void onEvent(String event) {
            View v = background.get(), r = row.get(); if (v == null || r == null) return;
            // A leaving card's region is driven by its exit (onPreDraw): applying the last blur
            // again on startAppearAnimation re-set a region about to be cut.
            if (glass != null && !exit.leaving(android.os.SystemClock.uptimeMillis())) glass.reassertBackdropIfLost();
            // Glass off: nothing to repair or report per event; the lifecycle stays in the opt-in trace.
            if (!enabled && !Probe.trace) return;
            Probe.view("ROW_EVENT", v);
            Probe.log("LIFECYCLE", "callback=" + event + " rowId=" + Integer.toHexString(System.identityHashCode(r))
                    + " headsUp=" + Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp") + " keyguard=" + Reflect.read(r, "mOnKeyguard")
                    + " pinned=" + Reflect.bool(r, "isPinned", "mIsPinned") + " animatingAway=" + Reflect.bool(r, "isHeadsUpAnimatingAway", "mHeadsUpAnimatingAway")
                    + " nativeBlur=" + Reflect.bool(v, "isBlurEnabled", "mBlurEnabled")
                    + " tint=" + Reflect.read(v, "mTintColor") + " radii=" + java.util.Arrays.toString(shape())
                    + " shadeExpanded=" + shadeExpanded + " crossBlur=" + blurEnabled);
            // Once per process: each report builds a blur drawable from the window, and it ran on
            // every attach (46 times in one 35 s log, 27/10).
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
            if (glass != null && (glassKind != kind || glass.stale() || !java.util.Objects.equals(source, glassSource))) {
                // A leaving card keeps the material it leaves with: the heads-up flag or the shade
                // state changing under its disappear animation rebuilt it (a new region, a frame
                // late, on a card about to go).
                if (CardExit.keepMaterial(exit.leaving(android.os.SystemClock.uptimeMillis()), true, glass.stale())) {
                    if (!(kind + "|" + source).equals(heldFor)) {
                        heldFor = kind + "|" + source;
                        Probe.log("MATERIAL_HELD", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " reason=leaving from="
                                + glassKind + "/" + glassSource + " to=" + kind + "/" + source);
                    }
                    kind = glassKind; source = glassSource;
                } else release();
            }
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
                // A new bridge takes the veil measured for this opening; a pop-up its native look or
                // measurement now, so its first frame is not drawn with the plain tint.
                pushToneVeil();
                if (compBridge != null && !pill) safeTone(v);
                if (semBridge != null) semBridge.setSuspended(stacked);
                glass = new GlassDrawable(backdrop, v.getResources().getDisplayMetrics().density, spec);
                glassWindow = new WeakReference<>(v.getWindowToken());
                heldFor = null;
                glass.setLightStyle(lightStyle, Tuning.get().lightEdge);
                glass.setSharedDarkFrost(darkNativeFrost);
                if (kind == Backdrop.Kind.SHARED) glass.setSharedVeil(measuredVeil, false);
                glass.setOutline(outline());
                inherited = true;
                glassKind = kind;
                glassSource = source;
                glass.setCallback(v);
                Probe.log("GLASS_APPLIED", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " source=" + backdrop.name()
                        + " optics=" + (kind == Backdrop.Kind.SAMPLED ? "refraction" : "edge_shader")
                        + " leaving=" + exit.leaving(android.os.SystemClock.uptimeMillis()));
                materialReported = false;
                Probe.painters(row.get());
            }
            boolean dark = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fill = kind == Backdrop.Kind.SHARED ? (dark ? spec.shadeDarkFill : spec.shadeLightFill) : (dark ? spec.darkFill : spec.lightFill);
            boolean live = Tuning.get().live && compBridge != null;
            // Pop-ups in the dark theme: the region colour and the black fill of the look approved on
            // 24/10 (livedarkalpha, livedarkfill); light glass keeps no fill. Lockscreen cards keep the
            // look approved before round 39 (liveDarkColor, no fill): applied there too, the darker
            // body and the doubled contour brought the second arc back (device screenshot, 25/10).
            boolean popup = popupSurface();
            // Lockscreen cards (dark theme) take the pop-up's scheme with their own values: a
            // clearer, darker body and a brighter outer line (kgdarkalpha, kgdarkfill, kgrim), with
            // the corner glint lowered (kgglint): at glint 1 the brighter rim of round 39 showed the
            // second arc again. The user found them milky next to the wallpaper (25/10).
            boolean lock = !popup && !pill && lensSurface();
            Tuning tn = Tuning.get();
            if (live && dark) fill = popup && !lightStyle ? tn.liveDarkFill << 24 : lock ? tn.kgDarkFill << 24 : 0;
            int tone = live && dark ? (popup ? (tn.liveDarkAlpha << 24 | 0xffffff) : lock ? (tn.kgDarkAlpha << 24 | 0xffffff)
                    : io.github.igorcv88.oneuiliquidglass.glass.MaterialMath.shadeDarkOpacity(spec.liveShadeDarkColor, tn.shadeDarkGlass))
                    : glass.hybrid() || kind == Backdrop.Kind.SAMSUNG || compBridge != null ? (dark ? spec.samsungDarkColor : spec.samsungLightColor)
                    : kind == Backdrop.Kind.SAMPLED ? (dark ? spec.captureDarkTint : spec.captureLightTint)
                    : dark ? spec.darkBlurColor : spec.lightBlurColor;
            glass.setEdgeEnabled(feature("edge"));
            glass.setPressed(pressed);
            refreshLens();
            // What the region actually shows for a veil of 0: the bridges replace the alpha when
            // debug.oulg.semalpha is set, and the ramp must start or end on that colour.
            int semAlpha = Tuning.get().semAlpha;
            nativeTint = semAlpha >= 0 && (compBridge != null || semBridge != null) ? (tone & 0x00ffffff) | (semAlpha << 24) : tone;
            // Unlike compositor blur regions, SharedBackdrop is drawn directly in the row.
            // Its native resting alpha is Theme Park-dependent, so only a verified exit fade
            // may attenuate the glass independently of the Android View alpha.
            boolean shadeLeaving = kind == Backdrop.Kind.SHARED && exit.leaving(android.os.SystemClock.uptimeMillis());
            View shadeRow = row.get();
            float shadeExitAlpha = shadeLeaving ? (shadeRow == null ? 0f : exitAlpha(shadeRow)) : Float.NaN;
            glass.configure(original, shape(), fill, tone, shadeExitAlpha);
            if (kind == Backdrop.Kind.SHARED && Probe.trace) {
                String signal = original.getAlpha() + ":" + glass.getAlpha() + ":" + shadeLeaving;
                if (!signal.equals(lastSharedAlpha)) {
                    lastSharedAlpha = signal;
                    Probe.log("SHADE_SHARED_ALPHA", "native=" + original.getAlpha()
                            + " glass=" + glass.getAlpha() + " leaving=" + shadeLeaving);
                }
            }
            if (live) {
                glass.setGlint(!feature("glint") ? 0f : popup ? tn.liveGlint : lock ? tn.kgGlint : !lensSurface() ? tn.shadeGlint : 1f);
                glass.setRimGain(popup ? tn.liveRim : lock ? tn.kgRim : 1f);
                updateLens(v); liveState(v, fill);
            }
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
            if (r == null || Tuning.get().shadeBlur && feature("per_card_blur")) return false;
            return Eligibility.sharedBackdrop(shadeExpanded,
                    Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"), barState);
        }
        /** Whether the card is a pop-up or lockscreen card (lens surface) rather than a shade card. */
        boolean lensSurface() {
            View r = row.get();
            return !pill && Eligibility.lens(barState, shadeExpanded, r == null ? null : Reflect.bool(r, "isOnKeyguard", "mOnKeyguard"));
        }
        /** A pop-up over an app: a lens surface outside the lockscreen. */
        boolean popupSurface() { return lensSurface() && barState != null && barState == Eligibility.BAR_SHADE; }
        /**
         * A pop-up over an app (not the shade, not the lockscreen) in LSPosed-only mode carries the
         * gated lens band (debug.oulg.livelens); every other surface, or a band whose capture became
         * unavailable, does not.
         */
        void updateLens(View v) {
            View r = row.get();
            Boolean keyguard = r == null ? null : Reflect.bool(r, "isOnKeyguard", "mOnKeyguard");
            Boolean headsUp = r == null ? null : Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp");
            boolean want = feature("lens") && Tuning.get().liveLens && !pill && !Boolean.TRUE.equals(keyguard)
                    && barState != null && barState == Eligibility.BAR_SHADE
                    && Eligibility.captureSurface(headsUp, keyguard, shadeExpanded)
                    && CaptureHub.available() && GlassDrawable.lensAvailable() && !glass.lensUnavailable();
            if (want == glass.hasLens()) return;
            // Frames are requested only while the band could show (BackdropMotion.wantsFrames): no
            // capture during motion or without a verified signal.
            int[] xy = new int[2];
            android.graphics.Rect card = new android.graphics.Rect();
            if (want) glass.setLens(new CaptureBackdrop(v, false, () -> {
                v.getLocationOnScreen(xy);
                card.set(xy[0], xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
                Tuning t = Tuning.get();
                return BackdropMotion.wantsFrames(card, android.os.SystemClock.uptimeMillis(), t.lensSettle, t.lensDirectional()) ? t.rimHz : 0;
            }));
            else glass.dropLens();
            Probe.log("LENS_BAND", "viewId=" + Integer.toHexString(System.identityHashCode(v)) + " on=" + glass.hasLens()
                    + " signal=" + BackdropMotion.active());
        }
        /** Last LIVE_STATE line of this card; logged again only when the material it describes changes. */
        String lastLiveState;
        /** Theme Park-dependent native alpha vs the shade's effective shared-glass alpha. */
        String lastSharedAlpha;
        /** Keyguard-to-shade routing changes, without per-frame logging. */
        String lastLockedShadeRoute;
        /**
         * What an LSPosed-only card asks the compositor for: the surface, the radius and colour sent to
         * the blur region, the fill, the cross-window capability (false on One UI 9 even when the blur
         * renders). Fades are left out: they change every frame of an animation. It says what was requested, not what reached the screen.
         */
        void liveState(View v, int fill) {
            int px = compBridge.requestedPx(), tint = compBridge.requestedTint();
            String surface = pill ? "pill" : lensSurface() ? (barState != null && barState != Eligibility.BAR_SHADE ? "lockscreen" : "popup") : "shade";
            String line = "surface=" + surface + " radius=" + px + " tint=" + Integer.toHexString(tint) + " fill=" + Integer.toHexString(fill)
                    + " veil=" + Integer.toHexString(measuredVeil) + " crossBlur=" + blurEnabled + " lens=" + (glass != null && glass.hasLens());
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
            boolean kgSurface = !pill && Eligibility.keyguardOptics(barState,
                    Reflect.bool(r, "isHeadsUpState", "mIsHeadsUp"), shadeExpanded);
            boolean lens = feature("lens") && !pill && Eligibility.lens(barState, shadeExpanded, keyguard);
            if (barState != null && (barState == Eligibility.BAR_KEYGUARD || barState == Eligibility.BAR_SHADE_LOCKED)) {
                String route = "bar=" + barState + " expanded=" + shadeExpanded + " rowKeyguard=" + keyguard
                        + " lens=" + lens + " lockOptics=" + kgSurface + " shared=" + sharedBackdrop();
                if (!route.equals(lastLockedShadeRoute)) {
                    lastLockedShadeRoute = route;
                    Probe.log("SHADE_LOCKED_ROUTE", "viewId=" + Integer.toHexString(r == null ? 0 : System.identityHashCode(r)) + " " + route);
                }
            }
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
            // Every path to the native background (hidden, ineligible, failed draw or compose)
            // passes here: the text gets its own colours back in the same frame. A ramp still running
            // must not apply its pending swap to a card that is going native.
            pendingSwap = null; pendingLightStyle = null;
            if (veilRamp != null) { veilRamp.cancel(); veilRamp = null; }
            // The shelf's text follows the shade's scene, not its glass: in the shade its native
            // background is drawn through material() -> release() on every frame, and restoring
            // here undid shelfTone() each redraw. shelfTone() restores it when the shade closes.
            if (!pill) setForeground(false);
            if (glass == null) return;
            // The blur guard also blocked calls aimed at the row while this material was managed.
            boolean compositor = glassKind == Backdrop.Kind.SAMSUNG || glass.hybrid() || compBridge != null;
            boolean drawable = compBridge != null;
            if (!restore && semBridge != null) semBridge.clearOnRelease();
            glass.release(); glass = null; glassKind = null; glassSource = null; semBridge = null; compBridge = null; glassWindow = null;
            View released = background.get();
            Probe.log("GLASS_RELEASED", "native=" + restore + " viewId=" + (released == null ? "null" : Integer.toHexString(System.identityHashCode(released)))
                    + " leaving=" + exit.leaving(android.os.SystemClock.uptimeMillis()) + " attached=" + (released != null && released.isAttachedToWindow()));
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
            if (!enabled) {
                // Glass off: no host walk or state for a card that never had glass; a card that has
                // glass or owes its native blur still goes through material(), which restores it.
                State known = states.get(v);
                if (known == null || known.minimal()) return;
            }
            long started = Perf.start(Perf.DRAW_BEFORE);
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
            Object real = p.getObjectExtra("oulgCanvas");
            boolean replaced = real instanceof Canvas;
            if (replaced) {
                long started = Perf.start(Perf.DRAW_AFTER);
                try { composeGlass(p, (Canvas) real); } finally { Perf.end(Perf.DRAW_AFTER, started); }
            }
            // ROW_FRAME describes the hierarchy, not which normal/dimmed sibling is drawn.
            // Report both the replaced and still-native paths, in actual draw order.
            if (Probe.trace && p.thisObject instanceof View) {
                Object result = p.getObjectExtra("oulgRendered");
                String render = result instanceof String ? (String) result : replaced ? "not-rendered" : "native";
                traceLayerDraw((View) p.thisObject, render, p.hasThrowable());
            }
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
                    p.setObjectExtra("oulgRendered", "native-recovered");
                    Probe.log("NATIVE_DRAW_RECOVERED", "glassDisabled=true");
                } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NATIVE_DRAW_RECOVERY_FAILED", e); }
                return;
            }
            try {
                Drawable current = (Drawable) backgroundField.get(v);
                GlassDrawable glass = (GlassDrawable) p.getObjectExtra("oulgGlass");
                android.graphics.Rect bounds = current.getBounds();
                if (bounds.isEmpty()) { p.setObjectExtra("oulgRendered", "empty-bounds"); return; }
                int save = canvas.save();
                try {
                    clipLikeNative(v, canvas);
                    glass.setBounds(bounds);
                    glass.draw(canvas);
                    p.setObjectExtra("oulgRendered", "glass");
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

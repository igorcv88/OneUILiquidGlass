package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.NotificationIconPolicy;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;

/**
 * One UI uses StatusBarIconView outside notification rows: the status bar, the lockscreen shelf,
 * and the collapsed "more notifications" shelf can render the same SBN through separate views.
 * Keep their native tint/slot/animation and replace only the drawable for eligible packages.
 */
final class NotificationCompactIconHooks {
    private static final String ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OULG-compact-icons");
        t.setDaemon(true);
        return t;
    });
    private static final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Method> installed = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final WeakHashMap<ImageView, State> states = new WeakHashMap<>();
    private final WeakHashMap<View, Integer> statusNativeWidths = new WeakHashMap<>();
    // The DZIF wrap-content frame grows, but its MATCH_PARENT linear host and icon
    // container can remain at Samsung's 216 px measurement. Restore their LPs on release.
    private final WeakHashMap<View, Integer> statusNativeLayoutWidths = new WeakHashMap<>();
    // One collapsed notification shelf may hold several icons: only one search/650 ms
    // across them; the count TextView keeps its own colour owner.
    private WeakReference<TextView> sharedShelfLabel = new WeakReference<>(null);
    private long nextShelfLabelLookup;
    private int shelfLabelMisses;
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final BooleanSupplier shelfToneSwapped;

    NotificationCompactIconHooks(ClassLoader loader, BooleanSupplier enabled,
                                 BooleanSupplier shelfToneSwapped) {
        this.loader = loader;
        this.enabled = enabled;
        this.shelfToneSwapped = shelfToneSwapped;
    }

    void install() {
        Class<?> type;
        try { type = Class.forName(ICON_VIEW, false, loader); }
        catch (ClassNotFoundException | LinkageError e) {
            Probe.log("ICON_TREAT_COMPACT_UNAVAILABLE", "class=" + ICON_VIEW);
            return;
        }
        XC_MethodHook updated = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!type.isInstance(param.thisObject) || !(param.thisObject instanceof ImageView)) return;
                ImageView image = (ImageView) param.thisObject;
                State state = states.get(image);
                if (state == null) {
                    state = new State(image);
                    states.put(image, state);
                    image.addOnAttachStateChangeListener(state);
                }
                state.refresh();
            }
        };
        hook(type, "setNotification", updated);
        hook(type, "set", updated);
        hook(type, "onAttachedToWindow", updated);
        installStatusLimit();
        installStatusInk();
    }

    /** Leave native light/dark transitions untouched unless the user selects a custom palette.
     * Apply at Samsung's DarkReceiver boundary, before the clock, battery, connectivity and
     * notification views each use the color. Missing OEM classes simply retain their defaults. */
    private void installStatusInk() {
        String[] names = {"com.android.systemui.statusbar.policy.QSClockIndicatorView",
                "com.android.systemui.statusbar.policy.Clock",
                "com.android.systemui.battery.BatteryMeterView",
                "com.android.systemui.statusbar.StatusBarWifiView",
                "com.android.systemui.statusbar.StatusBarMobileView", ICON_VIEW};
        for (String name : names) {
            Class<?> type;
            try { type = Class.forName(name, false, loader); }
            catch (ClassNotFoundException | LinkageError ignored) { continue; }
            hook(type, "onDarkChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled.getAsBoolean() || !(p.thisObject instanceof View)
                            || !inStatusBar((View) p.thisObject)) return;
                    Tuning tuning = Tuning.get();
                    if (tuning.statusIconInkMode == 0) return;
                    if (p.args.length != 3 || !(p.args[1] instanceof Float)
                            || !(p.args[2] instanceof Integer)) return;
                    boolean lightBackdrop = ((Float) p.args[1]) >= .5f;
                    int color = tuning.statusIconInkMode == 1 ? tuning.statusIconFixedInk
                            : lightBackdrop ? tuning.statusIconLightInk : tuning.statusIconDarkInk;
                    p.args[2] = color;
                    if (Probe.trace) Probe.log("ICON_TREAT_STATUS_INK", "class=" + type.getSimpleName()
                            + " mode=" + tuning.statusIconInkMode + " color=" + Integer.toHexString(color));
                }
            });
        }
    }

    private boolean inStatusBar(View target) {
        View v = target;
        for (int depth = 0; v != null && depth < 14; depth++) {
            String n = v.getClass().getName();
            if (n.contains("PhoneStatusBarView") || n.contains("CollapsedStatusBarView")) return true;
            int id = v.getId();
            if (id != View.NO_ID && id != 0) {
                try {
                    String resource = v.getResources().getResourceEntryName(id);
                    if (resource.equals("status_bar") || resource.equals("status_bar_contents")) return true;
                } catch (android.content.res.Resources.NotFoundException ignored) { }
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** One UI 9 uses shouldForceOverflow(int,float,int) plus a Compose status-bar ViewModel.
     * These are the integration points independently identified by OneUIX's StatusBar.kt.
     * Do not touch its separate FaceWidget/lockscreen icon-count path. */
    private void installStatusLimit() {
        Class<?> container;
        try { container = Class.forName("com.android.systemui.statusbar.phone.NotificationIconContainer", false, loader); }
        catch (ClassNotFoundException | LinkageError e) {
            Probe.log("ICON_TREAT_STATUS_LIMIT", "available=false reason=noContainer");
            return;
        }
        hook(container, "shouldForceOverflow", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!container.isInstance(p.thisObject) || !enabled.getAsBoolean()) return;
                if (Boolean.TRUE.equals(Reflect.read(p.thisObject, "mOnLockScreen"))
                        || Boolean.TRUE.equals(Reflect.read(p.thisObject, "mInNotificationIconShelf"))) return;
                // Only the One UI 8.5/9 overload uses this exact argument order.
                if (p.args.length != 3 || !(p.args[0] instanceof Integer)
                        || !(p.args[1] instanceof Float) || !(p.args[2] instanceof Integer)) return;
                int requested = Tuning.get().statusMaxIcons;
                if (requested <= 0) return; // Keep the untouched Samsung limit.
                p.args[2] = requested;
                if (Probe.trace) {
                    View panel = p.thisObject instanceof View ? (View) p.thisObject : null;
                    Probe.log("ICON_TREAT_STATUS_LIMIT", "route=shouldForceOverflow max=" + requested
                            + " arg0=" + p.args[0] + " arg1=" + p.args[1]
                            + " maxStatic=" + Reflect.read(p.thisObject, "mMaxStaticIcons")
                            + " width=" + (panel == null ? -1 : panel.getWidth())
                            + " children=" + (panel instanceof android.view.ViewGroup
                                    ? ((android.view.ViewGroup) panel).getChildCount() : -1));
                }
            }
        });

        // Status icons sometimes fall back to Samsung's native three-slot allocation after a
        // shade/keyguard transition. Observe the *final* container geometry, not just the
        // requested value passed to shouldForceOverflow (which may still be correct).
        hook(container, "onLayout", new XC_MethodHook() {
            private final WeakHashMap<View, String> last = new WeakHashMap<>();
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof ViewGroup)) return;
                ViewGroup panel = (ViewGroup) p.thisObject;
                if (!inStatusBar(panel)) return;
                reserveStatusOverflowWidth(panel);
                if (!Probe.trace || !enabled.getAsBoolean()) return;
                int shown = 0;
                for (int i = 0; i < panel.getChildCount(); i++)
                    if (panel.getChildAt(i).getVisibility() == View.VISIBLE) shown++;
                String state = "width=" + panel.getWidth() + " measured=" + panel.getMeasuredWidth()
                        + " children=" + panel.getChildCount() + " visible=" + shown
                        + " maxStatic=" + Reflect.read(panel, "mMaxStaticIcons")
                        + " actualWidth=" + Reflect.read(panel, "mActualLayoutWidth")
                        + " lockscreen=" + Reflect.read(panel, "mOnLockScreen")
                        + " shelf=" + Reflect.read(panel, "mInNotificationIconShelf")
                        + " requested=" + Tuning.get().statusMaxIcons;
                if (!state.equals(last.put(panel, state)))
                    Probe.log("ICON_TREAT_STATUS_LAYOUT", state);
            }
        });

        // The numeric cap and physical-space cap are separate checks in Android's
        // NotificationIconContainer. Log the latter's actual operands and result so we can
        // tell whether Samsung has reserved only three icon slots. Forcing this result to
        // false without changing geometry would render icons beyond the allocated region.
        hook(container, "isOverflowing", new XC_MethodHook() {
            private long lastLogged;
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!Probe.trace || !enabled.getAsBoolean() || Tuning.get().statusMaxIcons <= 0
                        || p.args.length != 4 || !(p.args[0] instanceof Boolean)
                        || !(p.args[1] instanceof Float) || !(p.args[2] instanceof Float)
                        || !(p.args[3] instanceof Float)
                        || !Boolean.TRUE.equals(p.getResult()) || !(p.thisObject instanceof View)) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLogged < 900) return;
                lastLogged = now;
                View panel = (View) p.thisObject;
                View garden = indicatorGarden(panel);
                float slot = (Float) p.args[3];
                int allocated = panel.getWidth();
                int requested = Tuning.get().statusMaxIcons;
                Probe.log("ICON_TREAT_STATUS_GEOMETRY", "overflow=true last=" + p.args[0]
                        + " x=" + p.args[1] + " end=" + p.args[2]
                        + " iconSize=" + slot + " width=" + allocated
                        + " actualWidth=" + Reflect.read(panel, "mActualLayoutWidth")
                        + " requested=" + requested
                        + " fitWithoutDot=" + (slot > 0 ? (int) (allocated / slot) : -1)
                        + " fitWithDot=" + (slot > 0 ? Math.max(0, (int) (allocated / slot) - 1) : -1)
                        + " requiredWithDot=" + (slot * (requested + 1))
                        + " gardenWidth=" + (garden == null ? -1 : garden.getWidth())
                        + " gardenMeasured=" + (garden == null ? -1 : garden.getMeasuredWidth())
                        + " gardenMax=" + (garden == null ? "?" : Reflect.read(garden, "mMaxWidth"))
                        + " parent=" + ancestors(panel)
                        + " allocation=" + allocationChain(panel));
            }
        });

        try {
            Class<?> viewModel = Class.forName(
                    "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel",
                    false, loader);
            Field maxIcons = Reflect.field(viewModel, "maxIcons");
            if (maxIcons == null) {
                Probe.log("ICON_TREAT_STATUS_LIMIT", "available=false reason=noViewModelMaxIcons");
                return;
            }
            for (java.lang.reflect.Constructor<?> ctor : viewModel.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!enabled.getAsBoolean() || Tuning.get().statusMaxIcons <= 0) return;
                            try {
                                maxIcons.setInt(p.thisObject, Integer.MAX_VALUE);
                                if (Probe.trace) Probe.log("ICON_TREAT_STATUS_LIMIT", "route=viewModelMaxIcons unlimited=true");
                            } catch (IllegalAccessException | RuntimeException e) {
                                Probe.log("ICON_TREAT_STATUS_LIMIT", "route=viewModelMaxIcons error=" + e.getClass().getSimpleName());
                            }
                        }
                    });
                } catch (RuntimeException | LinkageError e) { Probe.error("ICON_TREAT_COMPACT_HOOK_FAILED", e); }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            Probe.log("ICON_TREAT_STATUS_LIMIT", "available=false reason=noViewModel");
        }
    }

    private void hook(Class<?> type, String name, XC_MethodHook callback) {
        int count = 0;
        for (Class<?> c = type; c != null && c.getClassLoader() == loader; c = c.getSuperclass()) {
            Method[] methods;
            try { methods = c.getDeclaredMethods(); }
            catch (RuntimeException | LinkageError e) { break; }
            for (Method method : methods) {
                if (!name.equals(method.getName()) || !installed.add(method)) continue;
                try { XposedBridge.hookMethod(method, callback); count++; }
                catch (RuntimeException | LinkageError e) { Probe.error("ICON_TREAT_COMPACT_HOOK_FAILED", e); }
            }
            if (count != 0) break;
        }
        Probe.log("ICON_TREAT_COMPACT_HOOK", "method=" + name + " count=" + count);
    }

    /** Reserve a separate overflow-dot slot after the requested four real notification
     * glyphs. Samsung's 216 px wrap-content host fits four 54 px glyphs but replaces the
     * fourth with a dot whenever the fourth notification enters overflow calculation.
     * The garden has 617 px on DZIF; update only this status-bar wrap-content host.
     * Never force isOverflowing(false), which could draw outside the allocated host. */
    private void reserveStatusOverflowWidth(ViewGroup panel) {
        View host = statusWidthHost(panel);
        if (host == null) return;
        int requested = enabled.getAsBoolean() ? Tuning.get().statusMaxIcons : 0;
        int iconSize = 0;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child.getMeasuredWidth() > 0) { iconSize = child.getMeasuredWidth(); break; }
        }
        int needed = statusOverflowBudget(requested, iconSize, panel.getChildCount());
        View garden = indicatorGarden(panel);
        if (needed > 0 && garden != null && garden.getWidth() > 0
                && needed > garden.getWidth()) needed = 0;
        // Reserve the actual measured branch, not only its outer FrameLayout: Samsung's
        // nested MATCH_PARENT views were stuck at 216 px even while the frame was 270.
        ViewParent direct = panel.getParent();
        View inner = direct instanceof View ? (View) direct : null;
        setStatusMeasuredWidth(panel, needed);
        if (inner != null && "AlphaOptimizedLinearLayout".equals(inner.getClass().getSimpleName()))
            setStatusMeasuredWidth(inner, needed);
        if (needed > 0) {
            if (!statusNativeWidths.containsKey(host))
                statusNativeWidths.put(host, host.getMinimumWidth());
            if (host.getMinimumWidth() != needed) {
                host.setMinimumWidth(needed);
                if (Probe.trace) Probe.log("ICON_TREAT_STATUS_BUDGET",
                        "reserved=" + needed + " iconSize=" + iconSize
                                + " count=" + panel.getChildCount() + " requested=" + requested
                                + " before=" + panel.getWidth());
            }
        } else {
            Integer original = statusNativeWidths.remove(host);
            if (original != null && host.getMinimumWidth() != original)
                host.setMinimumWidth(original);
        }
    }

    /** One layout transition per size change; no per-frame requestLayout or forced overflow. */
    void setStatusMeasuredWidth(View view, int needed) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        if (needed > 0) {
            if (!statusNativeLayoutWidths.containsKey(view))
                statusNativeLayoutWidths.put(view, lp.width);
            if (lp.width != needed) {
                lp.width = needed;
                view.setLayoutParams(lp);
                if (Probe.trace) Probe.log("ICON_TREAT_STATUS_INNER_WIDTH",
                        "view=" + view.getClass().getSimpleName() + " width=" + needed);
            }
        } else {
            Integer original = statusNativeLayoutWidths.remove(view);
            if (original != null && lp.width != original) {
                lp.width = original;
                view.setLayoutParams(lp);
            }
        }
    }

    static int statusOverflowBudget(int requested, int iconSize, int children) {
        if (requested <= 0 || iconSize <= 0 || children < requested) return 0;
        long pixels = (long) (requested + 1) * iconSize;
        return pixels > Integer.MAX_VALUE ? 0 : (int) pixels;
    }

    private static View statusWidthHost(View panel) {
        View fallback = null;
        for (ViewParent p = panel.getParent(); p instanceof View; p = p.getParent()) {
            View v = (View) p;
            if (v.getLayoutParams() == null
                    || v.getLayoutParams().width != ViewGroup.LayoutParams.WRAP_CONTENT) continue;
            if ("AlphaOptimizedFrameLayout".equals(v.getClass().getSimpleName())) return v;
            if (fallback == null && v instanceof android.widget.FrameLayout) fallback = v;
        }
        return fallback;
    }

    private static String ancestors(View v) {
        StringBuilder result = new StringBuilder();
        for (ViewParent parent = v.getParent(); parent instanceof View && result.length() < 110;
             parent = parent.getParent()) {
            if (result.length() > 0) result.append('/');
            result.append(parent.getClass().getSimpleName());
        }
        return result.toString();
    }

    /** Report the first actual layout limit; the garden itself has room on DZIF (617 px). */
    private static String allocationChain(View start) {
        StringBuilder result = new StringBuilder();
        View current = start;
        for (int depth = 0; depth < 9 && current != null; depth++) {
            ViewGroup.LayoutParams params = current.getLayoutParams();
            if (result.length() > 0) result.append('>');
            result.append(current.getClass().getSimpleName())
                    .append('[').append(current.getWidth()).append('/').append(current.getMeasuredWidth())
                    .append(" lp=").append(params == null ? "null" : params.width);
            if (params instanceof android.widget.LinearLayout.LayoutParams)
                result.append(" wt=").append(((android.widget.LinearLayout.LayoutParams) params).weight);
            result.append(" pad=").append(current.getPaddingLeft() + current.getPaddingRight()).append(']');
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return result.toString();
    }

    /** The Samsung width budget, not just the numerical icon cap, constrains overflow. */
    private static View indicatorGarden(View v) {
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            View parent = (View) p;
            if ("IndicatorGardenMaxWidthFrameLayout".equals(parent.getClass().getSimpleName()))
                return parent;
        }
        return null;
    }

    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<ImageView> ref;
        ViewTreeObserver observer;
        String key;
        int mode = -1;
        long ticket;
        NotificationIconTreatment.Resolved ready;
        Drawable installed, nativeDrawable, nativeBackground;
        int backgroundAlpha = 255;
        int ink;
        String pkg;
        long rebindingWindow, suspendedUntil;
        int competingRebinds;
        String lastInkProbe;
        String lastShelfDecision;
        ColorFilter originalShelfFilter, appliedShelfFilter;
        int appliedShelfColor;
        long shelfOverwriteWindow, shelfSuspendUntil;
        int shelfOverwrites;
 
        State(ImageView view) { ref = new WeakReference<>(view); }

        @Override public void onViewAttachedToWindow(View v) { attach(); refresh(); }

        @Override public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            restore();
        }

        private void attach() {
            ImageView v = ref.get();
            if (v == null || !v.isAttachedToWindow() || observer != null) return;
            observer = v.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override public boolean onPreDraw() { refresh(); return true; }

        private StatusBarNotification notification(ImageView v) {
            Object candidate = null;
            try { candidate = Reflect.call(v, "getNotification"); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            if (!(candidate instanceof StatusBarNotification)) candidate = Reflect.read(v, "mNotification");
            return candidate instanceof StatusBarNotification ? (StatusBarNotification) candidate : null;
        }

        void refresh() {
            ImageView v = ref.get();
            if (v == null) return;
            attach();
            int currentMode = enabled.getAsBoolean() ? Tuning.get().iconMode : NotificationIconPolicy.OFF;
            if (currentMode == NotificationIconPolicy.OFF) { clear(); return; }
            StatusBarNotification sbn = notification(v);
            if (sbn == null || sbn.getNotification() == null || !v.isAttachedToWindow()) {
                // Some framework status slots have no notification. Never alter Wi-Fi, battery,
                // privacy or launcher icons that happen to use the same class.
                clear();
                return;
            }
            Notification n = sbn.getNotification();
            long config = ConfigClient.revision();
            String nextKey = NotificationIconTreatment.sourceKey(sbn.getPackageName(), n, currentMode, config);
            if (!nextKey.equals(key) || currentMode != mode) {
                restore();
                key = nextKey;
                mode = currentMode;
                pkg = sbn.getPackageName();
                ready = null;
                long task = ++ticket;
                Context context = v.getContext().getApplicationContext();
                // Snapshot before the worker, and only if this was still the framework drawable.
                final int[] nativeSnapshot = v.getDrawable() == null ? null : pixels(v.getDrawable());
                worker.execute(() -> {
                    NotificationIconTreatment.Resolved result = NotificationIconTreatment.resolveCompact(
                            context, sbn, currentMode, nativeSnapshot, config);
                    main.post(() -> {
                        ImageView image = ref.get();
                        if (image == null || ticket != task || !nextKey.equals(key)) return;
                        ready = result;
                        if (Probe.trace) Probe.log("ICON_TREAT_COMPACT_ROUTE", "pkg=" + pkg
                                + " source=" + (result == null ? "preserve" : result.source)
                                + " parent=" + ancestors(image));
                        if (result != null && result.bitmap != null) image.invalidate();
                    });
                });
            }
            // The shelf needs its dark/light glyph even when source resolution intentionally
            // preserves Samsung's native monochrome drawable (bitmap=null). That path used to
            // return before the shelf filter, leaving white icons on a light expanded shade.
            boolean shelfChild = inNotificationShelf(v);
            Integer labelInk = shelfChild ? shelfLabelInk(v) : null;
            boolean mirrorShelf = ShelfIconInk.shouldMirror(shelfChild,
                    shelfChild && shelfToneSwapped.getAsBoolean());
            // The compact shelf is also visible while shadeAdaptive is disabled (for example
            // while transitioning from keyguard). Its black label then remains native even
            // though the scene callback is false. The drawn label is the source of truth.
            boolean tintShelf = shelfChild;
            if (shelfChild && Probe.trace) {
                String state = "pkg=" + pkg + " mirror=" + mirrorShelf + " tint=" + tintShelf
                        + " label=" + (labelInk == null ? "missing" : Integer.toHexString(labelInk))
                        + " source=" + (ready == null ? "pending" : ready.source)
                        + " nativeInk=" + Integer.toHexString(currentInk(v))
                        + " viewFilter=" + (v.getColorFilter() != null);
                if (!state.equals(lastShelfDecision)) {
                    lastShelfDecision = state;
                    Probe.log("ICON_TREAT_SHELF_DECISION", state);
                }
            }
            int shelfInk = 0;
            if (tintShelf) {
                Tuning palette = Tuning.get();
                int sceneInk = ShelfIconInk.sceneInk(ShadeTone.lightText(), mirrorShelf);
                shelfInk = labelInk != null
                        ? ShelfIconInk.fromLabel(labelInk, palette.notificationIconInkMode,
                                palette.notificationIconFixedInk, palette.notificationIconLightInk,
                                palette.notificationIconDarkInk)
                        : ShelfIconInk.fromLabel(sceneInk, palette.notificationIconInkMode,
                                palette.notificationIconFixedInk, palette.notificationIconLightInk,
                                palette.notificationIconDarkInk);
            }
            if (ready == null || ready.bitmap == null) {
                if (tintShelf) applyShelfFilter(v, shelfInk);
                else restoreShelfFilter(v);
                return;
            }
            boolean rebound = installed != null && v.getDrawable() != installed;
            if (rebound) restore();
            if (v.getDrawable() != installed) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now < suspendedUntil) return;
                if (rebound) {
                    if (now - rebindingWindow > 600) { rebindingWindow = now; competingRebinds = 0; }
                    if (++competingRebinds > 6) {
                        suspendedUntil = now + 1200;
                        if (Probe.trace) Probe.log("ICON_TREAT_COMPACT_CONTESTED", "pkg=" + pkg);
                        return;
                    }
                }
                nativeDrawable = v.getDrawable();
                nativeBackground = v.getBackground();
                backgroundAlpha = nativeBackground == null ? 255 : nativeBackground.getAlpha();
                NotificationGlyphDrawable replacement = new NotificationGlyphDrawable(ready.bitmap,
                        Tuning.get().statusIconScale, nativeDrawable);
                installed = replacement;
                v.setImageDrawable(replacement);
                if (nativeBackground != null) nativeBackground.mutate().setAlpha(0);
                if (Probe.trace) Probe.log("ICON_TREAT_COMPACT_APPLIED", "pkg=" + pkg
                        + " source=" + ready.source + " parent=" + ancestors(v));
            }
            if (installed instanceof NotificationGlyphDrawable)
                ((NotificationGlyphDrawable) installed).scale(Tuning.get().statusIconScale);
            if (Probe.trace) {
                String current = "pkg=" + pkg + " parent=" + ancestors(v)
                        + " nativeInk=" + Integer.toHexString(currentInk(v))
                        + " viewFilter=" + (v.getColorFilter() != null)
                        + " tintList=" + (v.getImageTintList() != null)
                        + " statusMode=" + Tuning.get().statusIconInkMode;
                if (!current.equals(lastInkProbe)) {
                    lastInkProbe = current;
                    Probe.log("ICON_TREAT_COMPACT_INK", current);
                }
            }
            // The expanded notification shelf is a separate icon container. Samsung keeps
            // writing its native (often white) ImageView filter even when HeadsUpHooks has
            // switched its "N more notifications" label to dark text on a light glass card.
            // Match the label only for shelf children; status bar and keyguard are untouched.
            // A native rebind above may restore the old view filter. Apply the shelf ink
            // after replacement too, so the newly installed glyph is correct in its first frame.
            if (tintShelf) { applyShelfFilter(v, shelfInk); return; }
            restoreShelfFilter(v);
            // The system owns the status/shelf icon's foreground colour. Re-read its current tint
            // instead of pinning white (a black status bar over a light app must remain black).
            if (v.getColorFilter() != null || v.getImageTintList() != null) {
                // Samsung may tint the ImageView itself; its native tint must win, and will also
                // follow future status-bar light/dark transitions without extra module writes.
                if (installed.getColorFilter() != null) installed.clearColorFilter();
                return;
            }
            int nextInk = currentInk(v);
            Tuning tuning = Tuning.get();
            if (tuning.statusIconInkMode == 1) nextInk = tuning.statusIconFixedInk;
            else if (tuning.statusIconInkMode == 2) {
                boolean lightBackdrop = (nextInk & 0x00ffffff) == 0x00000000;
                nextInk = lightBackdrop ? tuning.statusIconLightInk : tuning.statusIconDarkInk;
            }
            if (nextInk != ink || installed.getColorFilter() == null) {
                ink = nextInk;
                installed.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
                v.invalidate();
            }
        }

        private void applyShelfFilter(ImageView v, int color) {
            ColorFilter current = v.getColorFilter();
            if (current == appliedShelfFilter && color == appliedShelfColor) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now < shelfSuspendUntil) return;
            if (appliedShelfFilter != null && current != appliedShelfFilter) {
                if (now - shelfOverwriteWindow > 700) {
                    shelfOverwriteWindow = now;
                    shelfOverwrites = 0;
                }
                if (++shelfOverwrites >= 8) {
                    shelfSuspendUntil = now + 1200;
                    if (Probe.trace) Probe.log("ICON_TREAT_SHELF_CONTESTED", "pkg=" + pkg);
                    return;
                }
            }
            originalShelfFilter = current == appliedShelfFilter ? originalShelfFilter : current;
            appliedShelfColor = color;
            appliedShelfFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            v.setColorFilter(appliedShelfFilter);
            if (Probe.trace) Probe.log("ICON_TREAT_SHELF_INK", "pkg=" + pkg
                    + " color=" + Integer.toHexString(color) + " original=" + Integer.toHexString(currentInk(v)));
        }

        private void restoreShelfFilter(ImageView v) {
            if (appliedShelfFilter == null) return;
            if (v != null && v.getColorFilter() == appliedShelfFilter)
                v.setColorFilter(originalShelfFilter);
            appliedShelfFilter = null;
            originalShelfFilter = null;
            appliedShelfColor = 0;
            shelfOverwrites = 0;
            shelfSuspendUntil = 0;
        }

        /** Follow only the actual "N more notifications" text, never shelf footer actions. */
        private Integer shelfLabelInk(ImageView icon) {
            TextView existing = sharedShelfLabel.get();
            if (existing != null && existing.isAttachedToWindow() && existing.isShown()
                    && ShelfIconInk.isCountLabel(resourceName(existing), existing.getText()))
                return existing.getCurrentTextColor();
            long now = android.os.SystemClock.uptimeMillis();
            if (now < nextShelfLabelLookup) return null;
            nextShelfLabelLookup = now + 650;
            // On DZIF the SecShelfNotificationIconContainer can sit outside
            // NotificationShelf. Follow its ancestors to the visible counter once per
            // 650 ms; never walk a notification row or log notification content.
            View origin = null;
            ViewParent ancestor = icon.getParent();
            for (int depth = 0; depth < 14 && ancestor instanceof View; depth++) {
                View node = (View) ancestor;
                String type = node.getClass().getSimpleName();
                if (origin == null && ("SecShelfNotificationIconContainer".equals(type)
                        || "NotificationShelf".equals(type))) origin = node;
                if (origin != null) {
                    TextView label = visibleShelfCountLabel(node, 0);
                    if (label != null) {
                        sharedShelfLabel = new WeakReference<>(label);
                        shelfLabelMisses = 0;
                        if (Probe.trace) Probe.log("ICON_TREAT_SHELF_LABEL", Foreground.describe(label)
                                + " source=" + type + " ink=" + Integer.toHexString(label.getCurrentTextColor()));
                        // Never write the label's colour here. Foreground/Samsung owns it.
                        return label.getCurrentTextColor();
                    }
                }
                if ("NotificationShadeWindowView".equals(type)
                        || "KeyguardRootView".equals(type)) break;
                ancestor = node.getParent();
            }
            if (Probe.trace && origin != null && ++shelfLabelMisses <= 3)
                Probe.log("ICON_TREAT_SHELF_LABEL", "missingCount=true anchor="
                        + Foreground.describe(origin) + " candidates="
                        + shelfTextCandidates(origin, 0, new int[]{0}));
            return null;
        }

        private static String resourceName(View v) {
            if (v.getId() == View.NO_ID) return null;
            try { return v.getResources().getResourceEntryName(v.getId()); }
            catch (RuntimeException ignored) { return null; }
        }

        private TextView visibleShelfCountLabel(View view, int depth) {
            if (depth > 13 || view.getVisibility() != View.VISIBLE) return null;
            if (view instanceof TextView && ShelfIconInk.isCountLabel(
                    resourceName(view), ((TextView) view).getText()) && view.isShown()) return (TextView) view;
            if (!(view instanceof android.view.ViewGroup)) return null;
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getClass().getSimpleName().endsWith("ExpandableNotificationRow")) continue;
                TextView found = visibleShelfCountLabel(child, depth + 1);
                if (found != null) return found;
            }
            return null;
        }

        private String shelfTextCandidates(View view, int depth, int[] count) {
            if (depth > 7 || count[0] >= 6 || view.getVisibility() != View.VISIBLE) return "";
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                CharSequence value = text.getText();
                count[0]++;
                return (resourceName(text) == null ? "no-id" : resourceName(text))
                        + "{len=" + (value == null ? 0 : value.length())
                        + ",shown=" + text.isShown() + ",ink="
                        + Integer.toHexString(text.getCurrentTextColor()) + "}";
            }
            if (!(view instanceof ViewGroup)) return "";
            ViewGroup group = (ViewGroup) view;
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < group.getChildCount() && count[0] < 6; i++) {
                View child = group.getChildAt(i);
                if (child.getClass().getSimpleName().endsWith("ExpandableNotificationRow")) continue;
                String found = shelfTextCandidates(child, depth + 1, count);
                if (found.isEmpty()) continue;
                if (list.length() > 0) list.append(',');
                list.append(found);
            }
            return list.toString();
        }

        private boolean inNotificationShelf(View v) {
            for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
                String name = p.getClass().getSimpleName();
                if ("SecShelfNotificationIconContainer".equals(name)
                        || "NotificationShelf".equals(name)) return true;
                if ("KeyguardRootView".equals(name) || "PhoneStatusBarView".equals(name)) return false;
            }
            return false;
        }

        private int[] pixels(Drawable drawable) {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
            Drawable d = drawable.getConstantState() == null ? drawable : drawable.getConstantState().newDrawable().mutate();
            android.graphics.Rect old = d.copyBounds();
            try {
                d.setBounds(0, 0, 24, 24);
                d.draw(new android.graphics.Canvas(b));
                int[] value = new int[24 * 24];
                b.getPixels(value, 0, 24, 0, 0, 24, 24);
                return value;
            } catch (RuntimeException | LinkageError e) { return null; }
            finally { d.setBounds(old); b.recycle(); }
        }

        private int currentInk(ImageView v) {
            Object color = Reflect.read(v, "mStaticDrawableColor");
            if (!(color instanceof Integer) || ((Integer) color) == 0)
                color = Reflect.read(v, "mCurrentSetColor");
            return color instanceof Integer && ((Integer) color) != 0 ? (Integer) color : Color.WHITE;
        }

        private void restore() {
            ImageView image = ref.get();
            if (image != null) {
                restoreShelfFilter(image);
                if (installed != null && image.getDrawable() == installed)
                    image.setImageDrawable(nativeDrawable);
                if (nativeBackground != null && image.getBackground() == nativeBackground
                        && nativeBackground.getAlpha() == 0) nativeBackground.setAlpha(backgroundAlpha);
            }
            installed = null;
            nativeDrawable = null;
            nativeBackground = null;
            ink = 0;
        }

        private void clear() {
            ticket++;
            restore();
            key = null;
            ready = null;
            mode = -1;
            pkg = null;
            suspendedUntil = 0;
            competingRebinds = 0;
            lastInkProbe = null;
            lastShelfDecision = null;
        }
    }
}

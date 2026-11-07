package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

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
    private final ClassLoader loader;
    private final BooleanSupplier enabled;

    NotificationCompactIconHooks(ClassLoader loader, BooleanSupplier enabled) {
        this.loader = loader;
        this.enabled = enabled;
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
                if (Probe.trace) Probe.log("ICON_TREAT_STATUS_LIMIT", "route=shouldForceOverflow max=" + requested);
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
            if (ready == null || ready.bitmap == null) return;
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

        private String ancestors(View v) {
            StringBuilder result = new StringBuilder();
            for (ViewParent parent = v.getParent(); parent instanceof View && result.length() < 110;
                 parent = parent.getParent()) {
                if (result.length() > 0) result.append('/');
                result.append(parent.getClass().getSimpleName());
            }
            return result.toString();
        }

        private void restore() {
            ImageView image = ref.get();
            if (image != null) {
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
        }
    }
}

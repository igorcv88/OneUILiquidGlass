package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
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
                if (!(param.thisObject instanceof ImageView)) return;
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
                BitmapDrawable replacement = new BitmapDrawable(v.getResources(), ready.bitmap);
                replacement.mutate();
                installed = replacement;
                v.setImageDrawable(replacement);
                if (nativeBackground != null) nativeBackground.mutate().setAlpha(0);
                if (Probe.trace) Probe.log("ICON_TREAT_COMPACT_APPLIED", "pkg=" + pkg
                        + " source=" + ready.source + " parent=" + ancestors(v));
            }
            // The system owns the status/shelf icon's foreground colour. Re-read its current tint
            // instead of pinning white (a black status bar over a light app must remain black).
            int nextInk = currentInk(v);
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

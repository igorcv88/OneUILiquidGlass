package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsMaterial;

/**
 * An independent, fail-closed Quick Settings surface. It never changes tile geometry, listeners,
 * gesture dispatch, icons or text; it decorates only the existing native backgrounds. Unlike the
 * notification shade, Samsung's QS controls may be rebuilt independently and have their own state.
 *
 * <p>Class names are probes, not assumptions about every One UI revision. Missing classes/methods
 * are skipped. The setting is off by default until verified on the S25 Ultra firmware.</p>
 */
public final class QuickSettingsHooks {
    private static final String TILE = "com.android.systemui.qs.tileimpl.SecQSTileView";
    private static final String TILE_BASE = "com.android.systemui.qs.tileimpl.SecQSTileBaseView";
    private static final String[] BARS = {
            "com.android.systemui.qs.bar.BrightnessBar",
            "com.android.systemui.qs.bar.VolumeBar",
            "com.android.systemui.qs.bar.QSMediaPlayerBar",
            "com.android.systemui.qs.bar.MediaDevicesBar"
    };
    private final ClassLoader loader;
    private final BooleanSupplier master;
    private final java.util.function.Consumer<android.content.Context> startConfig;
    private final WeakHashMap<View, Pane> panes = new WeakHashMap<>();
    private final Set<Method> installed = Collections.newSetFromMap(new java.util.HashMap<>());
    private static final int MAX_PANES = 36;

    public QuickSettingsHooks(ClassLoader loader, BooleanSupplier master,
            java.util.function.Consumer<android.content.Context> startConfig) {
        this.loader = loader;
        this.master = master;
        this.startConfig = startConfig;
    }

    private boolean enabled() {
        return master.getAsBoolean() && ConfigClient.flag(ProductFlags.QUICK_SETTINGS);
    }

    public void install() {
        Class<?> tile = resolve(TILE);
        Class<?> base = resolve(TILE_BASE);
        XC_MethodHook tileState = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof View) recordTile((View) p.thisObject);
                } catch (RuntimeException | LinkageError e) { Probe.error("QS_TILE_FAILED", e); }
            }
        };
        // Both generations exist: onStateChanged queues handleStateChanged through a Handler on
        // older firmware; a newer build may invoke handleStateChanged directly.
        hookDeclared(tile, "handleStateChanged", tileState);
        hookDeclared(base, "handleStateChanged", tileState);
        hookDeclared(base, "onStateChanged", tileState);
        for (String name : BARS) {
            Class<?> bar = resolve(name);
            hookDeclared(bar, "inflateViews", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object root = Reflect.read(p.thisObject, "mBarRootView");
                        if (root instanceof View) record((View) root, true, false, "bar/" + name.substring(name.lastIndexOf('.') + 1));
                    } catch (RuntimeException | LinkageError e) { Probe.error("QS_BAR_FAILED", e); }
                }
            });
        }
        Probe.log("QS_READY", "tile=" + (tile != null) + " base=" + (base != null) + " hooks=" + installed.size()
                + " optIn=" + ConfigClient.flag(ProductFlags.QUICK_SETTINGS));
    }

    private Class<?> resolve(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            Probe.log("QS_CLASS_MISSING", "class=" + name);
            return null;
        }
    }

    private void hookDeclared(Class<?> c, String name, XC_MethodHook h) {
        if (c == null) return;
        try {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !installed.add(method)) continue;
                try {
                    XposedBridge.hookMethod(method, h);
                    Probe.log("QS_HOOK", "method=" + method.toGenericString());
                } catch (RuntimeException | LinkageError e) { Probe.error("QS_HOOK_FAILED", e); }
            }
        } catch (RuntimeException | LinkageError e) { Probe.error("QS_METHODS_UNREADABLE", e); }
    }

    private void recordTile(View tile) {
        // Samsung's mBg is the circular drawable behind the small tile's icon. Large connectivity
        // tiles use the whole clickable pill, whose original RippleDrawable is kept as feedback.
        Object largeField = Reflect.read(tile, "mIsLargeView");
        if (!(largeField instanceof Boolean)) largeField = Reflect.read(tile, "mIsLargeTile");
        boolean large = Boolean.TRUE.equals(largeField);
        Object iconBackground = Reflect.read(tile, "mBg");
        View target = large || !(iconBackground instanceof ImageView) ? tile : (View) iconBackground;
        Object state = Reflect.read(tile, "mQSTileState");
        if (state == null) state = Reflect.read(tile, "mState");
        Object value = Reflect.read(state, "state");
        boolean active = value instanceof Integer ? (Integer) value == 2 : tile.isActivated();
        record(target, target == tile, active, large ? "large-tile" : "icon-tile");
    }

    private void record(View view, boolean nativeRipple, boolean active, String kind) {
        // A QS-only session may have no notification row: bootstrap the provider independently.
        startConfig.accept(view.getContext());
        Pane pane = panes.get(view);
        if (pane == null) {
            if (panes.size() >= MAX_PANES) return;
            pane = new Pane(view, nativeRipple, kind);
            panes.put(view, pane);
            view.addOnAttachStateChangeListener(pane);
        }
        pane.active = active;
        pane.apply();
    }

    /** Called on the ConfigProvider callback, including when the master switch is disabled. */
    public void refresh() {
        for (Pane pane : new ArrayList<>(panes.values())) pane.apply();
    }

    private final class Pane implements View.OnAttachStateChangeListener {
        private final WeakReference<View> ref;
        private final boolean nativeRipple;
        private final String kind;
        private boolean active;
        private Drawable original;
        private QuickSettingsMaterial material;
        private int originalImageAlpha = -1;
        private boolean failed;

        Pane(View v, boolean nativeRipple, String kind) {
            ref = new WeakReference<>(v);
            this.nativeRipple = nativeRipple;
            this.kind = kind;
        }

        void apply() {
            View view = ref.get();
            if (view == null) return;
            if (!enabled() || !view.isAttachedToWindow() || !view.isHardwareAccelerated()) {
                restore(view);
                return;
            }
            if (failed) return; // Never retry a broken ViewRoot factory on every state update.
            if (material != null && view.getBackground() != material) {
                // SystemUI or Theme Park replaced our background; its new drawable is native.
                restore(view);
            }
            if (material == null) {
                try {
                    original = view.getBackground();
                    material = new QuickSettingsMaterial(view, original, nativeRipple);
                    if (view instanceof ImageView) originalImageAlpha = ((ImageView) view).getImageAlpha();
                    view.setBackground(material);
                    material.onInstalled();
                    Probe.log("QS_GLASS", "kind=" + kind + " view=" + view.getClass().getName());
                } catch (ReflectiveOperationException | RuntimeException e) {
                    failed = true;
                    Probe.error("QS_GLASS_FALLBACK", e);
                    restore(view);
                    return;
                }
            }
            material.setActive(active);
            if (view instanceof ImageView) {
                // Leave the Samsung active/inactive tint and icon intact, just let the glass show
                // through the native opaque circle. No touch target or accessibility changes.
                int alpha = active ? 180 : 96;
                if (((ImageView) view).getImageAlpha() != alpha)
                    ((ImageView) view).setImageAlpha(alpha);
            }
        }

        private void restore(View view) {
            if (material != null) {
                Drawable restore = material.release();
                if (view.getBackground() == material) view.setBackground(restore);
                material = null;
                original = null;
            }
            if (view instanceof ImageView && originalImageAlpha >= 0) {
                ((ImageView) view).setImageAlpha(originalImageAlpha);
                originalImageAlpha = -1;
            }
        }

        @Override public void onViewAttachedToWindow(View v) {
            failed = false; // A fresh ViewRoot may have gained blur capability.
            apply();
        }
        @Override public void onViewDetachedFromWindow(View v) { restore(v); }
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;

/**
 * Installs the four ongoing surfaces (laudo 02/11/2026) from HeadsUpHooks, which already owns the
 * SystemUI entry and the config client; no second registration. Each surface resolves its own
 * exact members and installs independently: a missing member or a failing callback keeps that
 * surface native without touching the others. Nothing here hooks a framework class.
 */
public final class LiveSurfaceHooks {
    /** Failures of one surface's callbacks before it is put back to native for the process. */
    static final int FAILURE_BUDGET = 3;

    private final ClassLoader loader;
    private final BooleanSupplier master;
    private final Consumer<Context> config;
    private final HookSite site;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<LiveSurface, Integer> failures = new EnumMap<>(LiveSurface.class);
    private final Set<LiveSurface> broken = EnumSet.noneOf(LiveSurface.class);
    private final Set<LiveSurface> available = EnumSet.noneOf(LiveSurface.class);
    final LiveGlass glass;
    final LiveChipHooks chips;
    final LiveCardHooks cards;
    final NowBarHooks nowBar;
    final ToastHooks toasts;
    private boolean environment;

    public LiveSurfaceHooks(ClassLoader loader, BooleanSupplier master, Consumer<Context> config) {
        this(loader, master, config, new XposedHookSite(), null, null);
    }

    LiveSurfaceHooks(ClassLoader loader, BooleanSupplier master, Consumer<Context> config, HookSite site,
                     LiveGlass.Blur blur, LiveGlass.Policy policy) {
        this.loader = loader;
        this.master = master;
        this.config = config;
        this.site = site;
        glass = new LiveGlass(blur != null ? blur : new CompositorBlur(), this::allows, policy != null ? policy : LiveSurfaceHooks::glassAllowed);
        chips = new LiveChipHooks(glass);
        cards = new LiveCardHooks(glass);
        nowBar = new NowBarHooks(glass, site);
        toasts = new ToastHooks(glass);
        nowBar.setInstaller(this::installPlugin);
    }

    boolean allows(LiveSurface s) {
        return !broken.contains(s) && master.getAsBoolean() && ConfigClient.flag(s.flag);
    }
    boolean broken(LiveSurface s) { return broken.contains(s); }
    boolean available(LiveSurface s) { return available.contains(s); }

    /** Config changed (master or one of the opt-ins): every surface applies or restores. */
    public void refresh() {
        for (LiveSurface s : LiveSurface.values()) guard(s, () -> glass.refresh(s));
    }

    public void install() {
        installChips();
        installCards();
        installToasts();
        installNowBarBootstrap();
        Probe.log("LIVE_READY", "available=" + available + " optIn=" + ConfigClient.flag(LiveSurface.CHIP.flag)
                + "/" + ConfigClient.flag(LiveSurface.NOWBAR.flag) + "/" + ConfigClient.flag(LiveSurface.TOAST.flag));
    }

    // ---- L1 --------------------------------------------------------------------------------

    void installChips() {
        Class<?> adapter = HookSite.type(LiveChipHooks.ADAPTER, loader);
        Class<?> holder = HookSite.type("androidx.recyclerview.widget.RecyclerView$ViewHolder", loader);
        Method bind = HookSite.exact(adapter, "onBindViewHolder", void.class, holder, int.class);
        Method writer = HookSite.exact(adapter, "setChipBg", void.class, ViewGroup.class, ColorStateList.class);
        if (bind == null || writer == null) { unavailable(LiveSurface.CHIP, "adapter=" + (adapter != null) + " bind=" + (bind != null) + " setChipBg=" + (writer != null)); return; }
        OngoingDataSource source = OngoingDataSource.resolve(loader);
        if (source != null) chips.setLocator(source::at);
        Probe.log("LIVE_CHIP_DATA", "source=" + (source == null ? "list-fallback" : source.helper.getName() + (source.isStatic() ? "#static" : "#instance")));
        boolean ok = site.hook(bind, new HookSite.Callback() {
            @Override public void before(HookSite.Param p) {
                guard(LiveSurface.CHIP, () -> chips.bindStart(p.thisObject, p.args[0], (Integer) p.args[1]));
            }
            @Override public void after(HookSite.Param p) {
                guard(LiveSurface.CHIP, () -> {
                    ensure(LiveChipHooks.itemView(p.args[0]));
                    chips.bindEnd(p.thisObject, p.args[0], p.threw);
                });
            }
        });
        ok &= site.hook(writer, new HookSite.Callback() {
            @Override public void after(HookSite.Param p) {
                if (p.threw || !(p.args[0] instanceof View)) return;
                guard(LiveSurface.CHIP, () -> chips.chipBackground(p.thisObject, (View) p.args[0]));
            }
        });
        if (ok) available.add(LiveSurface.CHIP);
    }

    // ---- L2 --------------------------------------------------------------------------------

    void installCards() {
        Class<?> adapter = HookSite.type(LiveCardHooks.ADAPTER, loader);
        Class<?> stack = HookSite.type(LiveCardHooks.STACK, loader);
        Method getView = HookSite.exact(adapter, "getView", View.class, int.class, View.class, ViewGroup.class);
        Method bindView = HookSite.exact(adapter, "bindView", void.class, View.class, int.class);
        Method isMedia = HookSite.exact(adapter, "isMediaView", boolean.class, View.class);
        Method initNormal = HookSite.exact(adapter, "initNormalNotificationView", void.class, View.class);
        Method changeBg = HookSite.exact(stack, "changeCardBg", void.class, View.class, int.class);
        Method blurBg = HookSite.exact(stack, "setBlurBG", void.class, View.class, int.class);
        Method itemBg = HookSite.exact(stack, "updateItemBg", void.class);
        // Without Samsung's own media verdict there is no structural media exclusion: all native.
        if (isMedia == null || (getView == null && bindView == null)) {
            unavailable(LiveSurface.CARD, "adapter=" + (adapter != null) + " isMediaView=" + (isMedia != null) + " getView=" + (getView != null) + " bindView=" + (bindView != null));
            return;
        }
        cards.setIsMediaView(isMedia);
        cards.setHelper(OngoingDataSource.resolve(loader));
        boolean ok = true;
        if (getView != null) ok &= site.hook(getView, after(LiveSurface.CARD, p -> {
            if (p.result instanceof View) { ensure((View) p.result); cards.cardBound(p.thisObject, (View) p.result, (Integer) p.args[0]); }
        }));
        if (bindView != null) ok &= site.hook(bindView, after(LiveSurface.CARD, p -> {
            if (p.args[0] instanceof View) { ensure((View) p.args[0]); cards.cardBound(p.thisObject, (View) p.args[0], (Integer) p.args[1]); }
        }));
        HookSite.Callback writer = after(LiveSurface.CARD, p -> { if (p.args[0] instanceof View) cards.cardWriter((View) p.args[0]); });
        if (initNormal != null) site.hook(initNormal, writer);
        if (changeBg != null) site.hook(changeBg, writer);
        if (blurBg != null) site.hook(blurBg, writer);
        if (itemBg != null) site.hook(itemBg, after(LiveSurface.CARD, p -> { if (p.thisObject instanceof View) cards.stackUpdated((View) p.thisObject); }));
        Probe.log("LIVE_CARD_WRITERS", "changeCardBg=" + (changeBg != null) + " setBlurBG=" + (blurBg != null)
                + " updateItemBg=" + (itemBg != null) + " initNormal=" + (initNormal != null));
        if (ok) available.add(LiveSurface.CARD);
    }

    // ---- T1 --------------------------------------------------------------------------------

    void installToasts() {
        Class<?> factory = HookSite.type(ToastHooks.FACTORY, loader);
        Class<?> toast = HookSite.type(ToastHooks.TOAST, loader);
        Method create = HookSite.exact(factory, "createToast", toast, Context.class, Context.class, CharSequence.class, String.class, int.class, int.class);
        Method getView = HookSite.exact(toast, "getView", View.class);
        if (create == null || getView == null) { unavailable(LiveSurface.TOAST, "createToast=" + (create != null) + " getView=" + (getView != null)); return; }
        toasts.setGetView(getView);
        if (site.hook(create, after(LiveSurface.TOAST, p -> { View v = toasts.created(p.result); if (v != null) ensure(v); })))
            available.add(LiveSurface.TOAST);
    }

    // ---- L3 --------------------------------------------------------------------------------

    void installNowBarBootstrap() {
        Class<?> instance = HookSite.type(NowBarHooks.PLUGIN_INSTANCE, loader);
        Method load = HookSite.exact(instance, "loadPlugin", void.class);
        if (load == null) { unavailable(LiveSurface.NOWBAR, "pluginInstance=" + (instance != null) + " loadPlugin=false"); return; }
        if (site.hook(load, after(LiveSurface.NOWBAR, p -> nowBar.pluginLoaded(p.thisObject)))) available.add(LiveSurface.NOWBAR);
    }

    /** The hooks of one verified plugin ABI; every callback filters the exact ongoing class first. */
    void installPlugin(NowBarHooks.Abi abi) {
        HookSite.Callback bound = after(LiveSurface.NOWBAR, p -> {
            if (p.thisObject instanceof View && p.thisObject.getClass() == abi.ongoing) {
                ensure((View) p.thisObject);
                nowBar.cardBound(abi, (View) p.thisObject, p.args.length > 0 && abi.item.isInstance(p.args[0]) ? p.args[0] : null);
            }
        });
        HookSite.Callback restyled = after(LiveSurface.NOWBAR, p -> {
            if (p.thisObject instanceof View && p.thisObject.getClass() == abi.ongoing) nowBar.cardBound(abi, (View) p.thisObject, null);
        });
        HookSite.Callback stack = after(LiveSurface.NOWBAR, p -> { if (p.thisObject instanceof View) nowBar.stackChanged((View) p.thisObject); });
        for (Method m : new Method[]{abi.d0, abi.e0, abi.n, abi.u}) if (m != null) site.hook(m, bound);
        for (Method m : new Method[]{abi.viewMode, abi.expandedColor}) if (m != null) site.hook(m, restyled);
        for (Method m : new Method[]{abi.stackRefresh, abi.expandedState}) if (m != null) site.hook(m, stack);
        if (abi.gradientVisible != null) site.hook(abi.gradientVisible, new HookSite.Callback() {
            @Override public void before(HookSite.Param p) {
                if (!(p.thisObject instanceof View) || !(p.args[0] instanceof Boolean)) return;
                guard(LiveSurface.NOWBAR, () -> p.args[0] = nowBar.gradientVisible((View) p.thisObject, (Boolean) p.args[0]));
            }
        });
        main.post(() -> guard(LiveSurface.NOWBAR, () -> glass.refresh(LiveSurface.NOWBAR)));
    }

    // ---- shared ----------------------------------------------------------------------------

    private interface Body { void run(HookSite.Param p); }
    private HookSite.Callback after(LiveSurface s, Body body) {
        return new HookSite.Callback() {
            @Override public void after(HookSite.Param p) { if (!p.threw) guard(s, () -> body.run(p)); }
        };
    }

    /** Runs a callback of one surface; its failures count against that surface only. */
    void guard(LiveSurface s, Runnable r) {
        if (broken.contains(s)) return;
        try { r.run(); }
        catch (RuntimeException | LinkageError e) {
            int n = failures.getOrDefault(s, 0) + 1;
            failures.put(s, n);
            Probe.error("LIVE_CALLBACK_FAILED", s.name(), e);
            if (n >= FAILURE_BUDGET) {
                broken.add(s);
                try { glass.restoreAll(s, "failure"); } catch (RuntimeException | LinkageError ignored) { }
                Probe.log("LIVE_SURFACE_DISABLED", "surface=" + s + " failures=" + n + " fallback=native");
            }
        }
    }

    private void unavailable(LiveSurface s, String detail) {
        Probe.log("LIVE_UNAVAILABLE", "surface=" + s + " " + detail + " fallback=native");
    }

    /** First host with a Context: config client, blur capability and display listeners, once. */
    private void ensure(View v) {
        if (environment || v == null) return;
        environment = true;
        Context c = v.getContext();
        try { config.accept(c); } catch (RuntimeException | LinkageError e) { Probe.error("LIVE_CONFIG_FAILED", e); }
        try {
            WindowManager wm = c.getSystemService(WindowManager.class);
            if (wm != null) wm.addCrossWindowBlurEnabledListener(main::post, on -> {
                Probe.log("LIVE_BLUR_CAPABILITY", "enabled=" + on);
                glass.syncAll();
            });
        } catch (RuntimeException | LinkageError e) { Probe.error("LIVE_BLUR_LISTENER_FAILED", e); }
        try {
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            if (dm != null) dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int id) { }
                @Override public void onDisplayRemoved(int id) { }
                @Override public void onDisplayChanged(int id) { glass.syncAll(); }
            }, main);
        } catch (RuntimeException | LinkageError e) { Probe.error("LIVE_DISPLAY_LISTENER_FAILED", e); }
    }

    /** Now Bar keeps its native drawing in doze/AOD and with the display off: no region, no wakeup. */
    static boolean glassAllowed(LiveSurface s, View host) {
        if (s != LiveSurface.NOWBAR) return true;
        Display d = host.getDisplay();
        if (d == null) return false;
        int state = d.getState();
        return state == Display.STATE_ON || state == Display.STATE_VR;
    }

    /** Production region: ViewRootImpl's BackgroundBlurDrawable, only with cross-window blur on. */
    static final class CompositorBlur implements LiveGlass.Blur {
        @Override public BackgroundBlurBridge create(View host) throws ReflectiveOperationException { return BackgroundBlurBridge.create(host); }
        @Override public boolean enabled(View host) {
            WindowManager wm = host.getContext().getSystemService(WindowManager.class);
            return wm != null && wm.isCrossWindowBlurEnabled();
        }
    }
}

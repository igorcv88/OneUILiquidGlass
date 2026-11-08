package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.view.View;
import java.lang.reflect.Method;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Read-only lifecycle telemetry for a live notification that remains on screen after cancellation.
 * A Samsung ongoing entry and its plugin Now Bar card have separate owners: observing a cancelled
 * NotificationEntry is not proof that Samsung removed the card. Never dismiss, rebind or force a
 * refresh from these probes. Logging is gated by debug.oulg.trace and has no notification text.
 */
final class LiveRemovalDiagnostics {
    private static final String[] CONTROLLERS = {
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityController",
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper",
            "com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip.OngoingActivityDataHelper"
    };

    private final ClassLoader loader;
    private final HookSite site;
    private final LiveGlass glass;
    private final NowBarHooks nowBar;
    private final Handler main;

    LiveRemovalDiagnostics(ClassLoader loader, HookSite site, LiveGlass glass, NowBarHooks nowBar,
                           Handler main) {
        this.loader = loader;
        this.site = site;
        this.glass = glass;
        this.nowBar = nowBar;
        this.main = main;
    }

    /** Hook only named Samsung removal callbacks; signatures vary by One UI build. */
    void install() {
        int count = 0;
        for (String name : CONTROLLERS) {
            Class<?> type = HookSite.type(name, loader);
            if (type == null) continue;
            Method[] methods;
            try { methods = type.getDeclaredMethods(); }
            catch (RuntimeException | LinkageError e) {
                Probe.error("LIVE_REMOVAL_DISCOVERY_FAILED", name, e);
                continue;
            }
            for (Method method : methods) {
                if (!removalMethod(method.getName()) || method.getParameterCount() > 3) continue;
                try {
                    method.setAccessible(true);
                    if (site.hook(method, new HookSite.Callback() {
                        @Override public void after(HookSite.Param p) {
                            if (p.threw || !Probe.trace) return;
                            try {
                                // Capture only an identifier and package; do not retain Samsung's
                                // NotificationEntry, ongoing data or PendingIntent in delayed work.
                                String identity = identity(p.args);
                                String route = p.method.getDeclaringClass().getSimpleName() + "."
                                        + p.method.getName();
                                main.post(() -> safeSample(route, identity, 0));
                                main.postDelayed(() -> safeSample(route, identity, 500), 500);
                                main.postDelayed(() -> safeSample(route, identity, 1800), 1800);
                            } catch (RuntimeException | LinkageError e) {
                                Probe.error("LIVE_REMOVAL_CALLBACK_FAILED", e);
                            }
                        }
                    })) count++;
                } catch (RuntimeException | LinkageError e) {
                    Probe.error("LIVE_REMOVAL_HOOK_FAILED", method.getName(), e);
                }
            }
        }
        Probe.log("LIVE_REMOVAL_HOOKS", "installed=" + count + " traceOnly=true mutation=false");
    }

    private void safeSample(String route, String identity, int delayMs) {
        try { sample(route, identity, delayMs); }
        catch (RuntimeException | LinkageError e) {
            Probe.error("LIVE_REMOVAL_SAMPLE_FAILED", e);
        }
    }

    static boolean removalMethod(String name) {
        return "onEntryRemoved".equals(name) || "removeOngoingActivity".equals(name)
                || "notifyRemoveItemNowbarObservers".equals(name)
                || "removeNowbarItem".equals(name);
    }

    private static String identity(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return "key=? pkg=?";
        Object first = args[0];
        Object sbn = first instanceof StatusBarNotification ? first : NativeShape.call(first, "getSbn");
        String pkg = sbn instanceof StatusBarNotification
                ? ((StatusBarNotification) sbn).getPackageName() : "?";
        Object key = first instanceof String ? first : NativeShape.call(first, "getKey");
        // Hashes distinguish different notification instances while keeping their keys private.
        return "keyHash=" + (key instanceof String
                ? Integer.toHexString(((String) key).hashCode()) : "?") + " pkg=" + pkg;
    }

    private void sample(String route, String identity, int delayMs) {
        if (!Probe.trace) return;
        Probe.log("LIVE_REMOVAL_STATE", "route=" + route + " delayMs=" + delayMs + " " + identity
                + " chip=" + hostCounts(LiveSurface.CHIP)
                + " card=" + hostCounts(LiveSurface.CARD)
                + " nowbar=" + hostCounts(LiveSurface.NOWBAR)
                + " plugin=" + nowBar.diagnosticSummary());
    }

    private String hostCounts(LiveSurface surface) {
        int total = 0, shown = 0, applied = 0;
        for (View host : glass.hosts(surface)) {
            total++;
            if (host.isAttachedToWindow() && host.isShown()) shown++;
            if (glass.applied(host)) applied++;
        }
        return total + "/" + shown + "/" + applied;
    }
}

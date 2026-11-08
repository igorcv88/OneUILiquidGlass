package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/**
 * Read-only tracing of the admission path, including notifications that never become a chip.
 * Samsung's ongoing controller only sees promoted entries; NotifCollection sees the preceding
 * publication. No notifications are changed, re-posted, retained or promoted by these hooks.
 * All events omit notification text, actions and pending intents, and require debug.oulg.trace.
 */
final class LiveAdmissionDiagnostics {
    private static final String COLLECTION =
            "com.android.systemui.statusbar.notification.collection.NotifCollection";
    private static final String LISTENER = "com.android.systemui.statusbar.NotificationListener";
    private static final String CONTROLLER =
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityController";
    private static final String HELPER =
            "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper";

    private final ClassLoader loader;
    private final HookSite site;
    private final Map<String, Long> lastLog = new HashMap<>();

    LiveAdmissionDiagnostics(ClassLoader loader, HookSite site) {
        this.loader = loader;
        this.site = site;
    }

    void install() {
        int source = hook(COLLECTION, "onNotificationPosted", true)
                + hook(LISTENER, "onNotificationPosted", true);
        int controller = hook(CONTROLLER, "onEntryUpdated", false);
        int helper = hook(HELPER, "updateOngoingActivity", false)
                + hook(HELPER, "notifyAddItemNowbarObservers", false)
                + hook(HELPER, "notifyUpdateItemNowbarObservers", false);
        Probe.log("LIVE_ADMISSION_HOOKS", "source=" + source + " controller=" + controller
                + " helper=" + helper + " traceOnly=true mutation=false");
    }

    private int hook(String owner, String name, boolean source) {
        Class<?> type = HookSite.type(owner, loader);
        if (type == null) return 0;
        Method[] methods;
        try { methods = type.getDeclaredMethods(); }
        catch (RuntimeException | LinkageError e) { return 0; }
        int count = 0;
        for (Method m : methods) {
            if (!name.equals(m.getName()) || m.getParameterCount() > 3 || m.getParameterCount() == 0)
                continue;
            if (source && !StatusBarNotification.class.isAssignableFrom(m.getParameterTypes()[0]))
                continue;
            try {
                m.setAccessible(true);
                if (site.hook(m, new HookSite.Callback() {
                    @Override public void after(HookSite.Param p) {
                        if (!Probe.trace || p.threw) return;
                        try { record(p, source); }
                        catch (RuntimeException | LinkageError e) {
                            Probe.error("LIVE_ADMISSION_TRACE_FAILED", p.method.getName(), e);
                        }
                    }
                })) count++;
            } catch (RuntimeException | LinkageError e) {
                if (Probe.trace) Probe.error("LIVE_ADMISSION_HOOK_FAILED", name, e);
            }
        }
        return count;
    }

    private void record(HookSite.Param p, boolean source) {
        Object first = p.args[0];
        StatusBarNotification sbn = first instanceof StatusBarNotification
                ? (StatusBarNotification) first : asSbn(first);
        Object data = first;
        String rawKey = first instanceof String ? (String) first : null;
        String pkg = sbn != null ? sbn.getPackageName() : asString(Reflect.read(data, "mPackageName"));
        if (pkg == null) pkg = packageFromKey(rawKey);
        if (!interesting(pkg)) return;
        String key = sbn != null ? sbn.getKey() : rawKey != null ? rawKey
                : asString(Reflect.read(data, "mNotiID"));
        String keyHash = key == null ? "?" : Integer.toHexString(key.hashCode());
        String stage = source ? "SOURCE" : p.method.getDeclaringClass().getSimpleName() + "." + p.method.getName();
        String unique = stage + "/" + pkg + "/" + keyHash;
        long now = SystemClock.elapsedRealtime();
        synchronized (lastLog) {
            Long previous = lastLog.get(unique);
            if (previous != null && now - previous < 900) return;
            if (lastLog.size() > 64) lastLog.clear();
            lastLog.put(unique, now);
        }
        if (sbn != null) {
            Notification n = sbn.getNotification();
            boolean requested = n.extras != null
                    && n.extras.getBoolean("android.requestPromotedOngoing", false);
            Probe.log("LIVE_ADMISSION", "stage=" + stage + " pkg=" + pkg + " keyHash=" + keyHash
                    + " flags=0x" + Integer.toHexString(n.flags)
                    + " ongoing=" + ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                    + " requestPromoted=" + requested + " category=" + n.category);
        } else {
            // Helper data can be read without materialising a RemoteViews or notification body.
            Probe.log("LIVE_ADMISSION", "stage=" + stage + " pkg=" + pkg + " keyHash=" + keyHash
                    + " cardIcon=" + (Reflect.read(data, "mCardIcon") != null)
                    + " nowbarIcon=" + (Reflect.read(data, "mNowbarIcon") != null));
        }
    }

    private static StatusBarNotification asSbn(Object entry) {
        if (entry == null) return null;
        Object value = Reflect.read(entry, "mSbn");
        if (!(value instanceof StatusBarNotification)) {
            try { value = Reflect.call(entry, "getSbn"); }
            catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        return value instanceof StatusBarNotification ? (StatusBarNotification) value : null;
    }

    private static String asString(Object value) { return value instanceof String ? (String) value : null; }

    /** Samsung's notification keys include the package between the first two pipe separators. */
    static String packageFromKey(String key) {
        if (key == null) return null;
        int first = key.indexOf('|');
        if (first < 0) return null;
        int second = key.indexOf('|', first + 1);
        return second < 0 ? null : key.substring(first + 1, second);
    }

    /** First pass is limited to the two apps already confirmed in the 7 November reproduction. */
    static boolean interesting(String pkg) {
        return "com.sec.android.app.clockpackage".equals(pkg) || "com.igcv.gradus".equals(pkg);
    }
}

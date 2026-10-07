package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Log-only survey of SystemUI windows and their visible view classes, taken shortly after a
 * notification arrives. Locates pop-up renderers that bypass ExpandableNotificationRow.
 */
public final class WindowSurvey {
    private static final long[] DELAYS_MS = {400, 1500};
    private static final int MAX_LINES_PER_WINDOW = 250;
    private static final long DEBOUNCE_MS = 2000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastScheduled;
    private static int surveys;

    private WindowSurvey() {}

    /** Main thread only. */
    public static void schedule(String trigger) {
        long now = SystemClock.uptimeMillis();
        if (now - lastScheduled < DEBOUNCE_MS) return;
        lastScheduled = now;
        int id = ++surveys;
        for (long delay : DELAYS_MS) MAIN.postDelayed(() -> run(id, trigger, delay), delay);
    }

    private static void run(int id, String trigger, long delay) {
        try {
            Object global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null);
            @SuppressWarnings("unchecked") List<View> views = (List<View>) field(global, "mViews");
            @SuppressWarnings("unchecked") List<WindowManager.LayoutParams> params = (List<WindowManager.LayoutParams>) field(global, "mParams");
            if (views == null || params == null) { Probe.log("SURVEY_FAILED", "id=" + id + " reason=noWindowLists"); return; }
            Probe.log("SURVEY", "id=" + id + " trigger=" + trigger + " delayMs=" + delay + " windows=" + views.size());
            for (int i = 0; i < views.size() && i < params.size(); i++) {
                View root = views.get(i);
                WindowManager.LayoutParams lp = params.get(i);
                Probe.log("SURVEY_WINDOW", "id=" + id + " w=" + i + " title=" + lp.getTitle() + " type=" + lp.type
                        + " shown=" + root.isShown() + " size=" + root.getWidth() + "x" + root.getHeight() + " root=" + root.getClass().getName());
                if (!root.isShown()) continue;
                int[] budget = {MAX_LINES_PER_WINDOW};
                walk(id, i, root, 0, new HashSet<>(), budget);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { Probe.error("SURVEY_FAILED", e); }
    }

    private static void walk(int id, int window, View v, int depth, Set<String> seen, int[] budget) {
        if (budget[0] <= 0 || depth > 24 || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        String key = depth + ":" + v.getClass().getName();
        if (seen.add(key)) {
            budget[0]--;
            int[] xy = new int[2]; v.getLocationOnScreen(xy);
            Probe.log("SURVEY_VIEW", "id=" + id + " w=" + window + " depth=" + depth + " class=" + v.getClass().getName()
                    + " xy=" + xy[0] + "," + xy[1] + " size=" + v.getWidth() + "x" + v.getHeight() + " bg=" + (v.getBackground() == null ? "null" : v.getBackground().getClass().getName()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int c = 0; c < g.getChildCount(); c++) walk(id, window, g.getChildAt(c), depth + 1, seen, budget);
        }
    }

    /**
     * Log every window SystemUI adds and survey it; One UI 9 shows pop-ups in EdgeLightingWindow,
     * not in NotificationShade. Log-only: the hook never changes arguments or results.
     */
    public static void installWindowHook() {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            de.robv.android.xposed.XposedBridge.hookAllMethods(global, "addView", new de.robv.android.xposed.XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 2 || !(p.args[0] instanceof View) || !(p.args[1] instanceof WindowManager.LayoutParams)) return;
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                        String title = String.valueOf(lp.getTitle());
                        Probe.log("WINDOW_ADDED", "title=" + title + " type=" + lp.type + " root=" + p.args[0].getClass().getName()
                                + " failed=" + p.hasThrowable());
                        if (title.contains("EdgeLighting") || title.contains("HeadsUp") || title.contains("Popup")) {
                            lastScheduled = 0;
                            schedule("window:" + title);
                        }
                    } catch (RuntimeException e) { Probe.error("WINDOW_HOOK_FAILED", e); }
                }
            });
            Probe.log("WINDOW_HOOK", "installed=true");
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) { Probe.error("WINDOW_HOOK_FAILED", e); }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only map of every media surface SystemUI shows, before any glass is designed for them:
 * the shade's media player, the media card inside the Live Updates stack, and the lockscreen
 * Now Bar media capsule (NowBarMediaCardView, kept native by LiveEligibility.nowBar). None of
 * their hierarchies has been captured on DZIF yet.
 *
 * <p>Off by default: one {@code debug.oulg.mediaprobe} read per second on the main thread and no
 * traversal. With {@code setprop debug.oulg.mediaprobe 1}, once per second every shown SystemUI
 * window is searched for media roots (class or resource name containing "media"), and each root
 * is described with its ancestors and its subtree: classes (the waveform's included), resource
 * names, geometry, visibility, alpha, elevation, clipping, layer type, backgrounds,
 * foregrounds and image drawable classes. A root is logged again only when its description
 * changed. Nothing is changed; no text, bitmap or notification content is read.</p>
 */
public final class LiveMediaSurvey {
    static final String PROPERTY = "debug.oulg.mediaprobe";
    private static final long PERIOD_MS = 1000;
    private static final int MAX_DEPTH = 16, MAX_NODES_PER_ROOT = 140, MAX_ROOTS = 8, MAX_ANCESTORS = 8;
    private static final long MIN_REPEAT_MS = 3000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Integer, Integer> signatures = new HashMap<>();
    private static final Map<Integer, Long> lastLogged = new HashMap<>();
    private static Method getter;
    private static boolean started;
    private static int scans;

    private LiveMediaSurvey() {}

    /** Idempotent; from the hook installation. */
    public static void start() {
        if (started) return;
        started = true;
        MAIN.postDelayed(LiveMediaSurvey::tick, PERIOD_MS);
    }

    private static void tick() {
        int lockMode = 0;
        try {
            lockMode = LockMediaProbe.mode();
            boolean survey = enabled();
            if (survey || lockMode != 0 || LockMediaProbe.hasSessions()) scan(survey, lockMode);
            else if (!signatures.isEmpty()) { signatures.clear(); lastLogged.clear(); }
        } catch (RuntimeException | LinkageError e) {
            Probe.log("LIVE_MEDIA_SURVEY_ERROR", "type=" + e.getClass().getSimpleName());
        } finally {
            // A vanished lockscreen card or a property switch must restore its native alphas
            // even if the window traversal above failed.
            try { LockMediaProbe.endScan(lockMode); }
            catch (RuntimeException | LinkageError e) {
                Probe.log("LOCK_MEDIA_PROBE_ERROR", "type=" + e.getClass().getSimpleName());
            }
            MAIN.postDelayed(LiveMediaSurvey::tick, PERIOD_MS);
        }
    }

    private static boolean enabled() {
        try {
            if (getter == null) getter = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return "1".equals(getter.invoke(null, PROPERTY));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void scan(boolean survey, int lockMode) throws RuntimeException {
        LockMediaProbe.beginScan();
        List<View> views;
        List<WindowManager.LayoutParams> params;
        try {
            Object global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null);
            views = (List<View>) field(global, "mViews");
            params = (List<WindowManager.LayoutParams>) field(global, "mParams");
        } catch (ReflectiveOperationException e) {
            Probe.log("LIVE_MEDIA_SURVEY_ERROR", "type=noWindowLists");
            return;
        }
        if (views == null || params == null) return;
        int scan = ++scans;
        int[] roots = {0};
        for (int w = 0; w < views.size() && w < params.size() && roots[0] < MAX_ROOTS; w++) {
            View root = views.get(w);
            if (root == null || !root.isShown()) continue;
            String title = String.valueOf(params.get(w).getTitle());
            find(root, title, scan, roots, 0, survey, lockMode);
        }
    }

    /** Media survey roots plus the exact AOD plugin media card for opt-in layer isolation. */
    private static void find(View v, String window, int scan, int[] roots, int depth,
                             boolean survey, int lockMode) {
        if (roots[0] >= MAX_ROOTS || depth > 40 || !v.isShown()) return;
        if (LockMediaProbe.isLockMedia(v)) {
            roots[0]++;
            if (survey) describeRoot(v, window, scan);
            if (lockMode != 0) LockMediaProbe.observe(v, lockMode, window);
            return;
        }
        if (survey && media(v)) {
            roots[0]++;
            describeRoot(v, window, scan);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                find(g.getChildAt(i), window, scan, roots, depth + 1, survey, lockMode);
        }
    }

    static boolean media(View v) {
        String cls = v.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (cls.contains("media")) return true;
        return LiveStackExternalProbe.resourceName(v).toLowerCase(java.util.Locale.ROOT).contains("media");
    }

    private static void describeRoot(View root, String window, int scan) {
        StringBuilder all = new StringBuilder(4096);
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        Object p = root.getParent();
        for (int i = 1; i <= MAX_ANCESTORS && p instanceof View; i++) {
            View a = (View) p;
            lines.add("role=ancestor depth=-" + i + " " + node(a));
            p = a.getParent();
        }
        int[] budget = {MAX_NODES_PER_ROOT};
        subtree(root, "r", 0, lines, budget);
        for (String l : lines) all.append(l).append('\n');
        int key = System.identityHashCode(root);
        int sig = all.toString().hashCode();
        Integer old = signatures.get(key);
        long now = android.os.SystemClock.uptimeMillis();
        Long last = lastLogged.get(key);
        if (old != null && old == sig) return;
        if (last != null && now - last < MIN_REPEAT_MS) return;
        signatures.put(key, sig);
        lastLogged.put(key, now);
        String id = Integer.toHexString(key);
        Probe.log("LIVE_MEDIA_SURVEY_ROOT", "scan=" + scan + " root=" + id + " window=" + window
                + " class=" + root.getClass().getName() + " res=" + LiveStackExternalProbe.resourceName(root)
                + " nodes=" + (MAX_NODES_PER_ROOT - budget[0]) + " truncated=" + (budget[0] <= 0));
        for (String l : lines) Probe.log("LIVE_MEDIA_SURVEY_NODE", "root=" + id + " " + l);
        Probe.log("LIVE_MEDIA_SURVEY_END", "root=" + id);
    }

    private static void subtree(View v, String path, int depth, java.util.List<String> out, int[] budget) {
        if (v == null || depth > MAX_DEPTH || budget[0]-- <= 0) return;
        out.add("role=node path=" + path + " " + node(v));
        if (v instanceof ViewGroup && v.getVisibility() != View.GONE) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++)
                subtree(g.getChildAt(i), path + "." + i, depth + 1, out, budget);
        }
    }

    /** One view: identity, geometry, visual state and drawables. No text, no bitmap content. */
    static String node(View v) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        StringBuilder b = new StringBuilder(256)
                .append("class=").append(v.getClass().getName())
                .append(" res=").append(LiveStackExternalProbe.resourceName(v))
                .append(" obj=").append(Integer.toHexString(System.identityHashCode(v)))
                .append(" xy=").append(xy[0]).append(',').append(xy[1])
                .append(" wh=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility())
                .append(" alpha=").append(v.getAlpha())
                .append(" tAlpha=").append(v.getTransitionAlpha())
                .append(" s=").append(v.getScaleX()).append('/').append(v.getScaleY())
                .append(" t=").append(v.getTranslationX()).append('/').append(v.getTranslationY())
                .append(" elev=").append(v.getElevation()).append(" z=").append(v.getZ())
                .append(" clipOutline=").append(v.getClipToOutline())
                .append(" layer=").append(v.getLayerType())
                .append(" willNotDraw=").append(v.willNotDraw());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            b.append(" children=").append(g.getChildCount())
                    .append(" clipChildren=").append(g.getClipChildren());
        }
        // No render effect field: the framework has setRenderEffect but no public getter, so the
        // state is not observable and a placeholder would only mislead.
        b.append(" bg=").append(LiveStackTimeline.drawable(v.getBackground()));
        b.append(" fg=").append(LiveStackTimeline.drawable(v.getForeground()));
        if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            Drawable d = iv.getDrawable();
            b.append(" image=").append(d == null ? "null"
                    : d.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(d))
                    + " intrinsic=" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                    + " alpha=" + d.getAlpha())
                    .append(" imageAlpha=").append(iv.getImageAlpha())
                    .append(" scaleType=").append(iv.getScaleType())
                    .append(" imageTint=").append(iv.getImageTintList() == null ? "null"
                            : Integer.toHexString(iv.getImageTintList().getDefaultColor()));
        }
        return b.toString();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
}

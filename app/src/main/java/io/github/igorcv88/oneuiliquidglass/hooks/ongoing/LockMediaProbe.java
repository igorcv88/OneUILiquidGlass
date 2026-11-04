package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Temporary, opt-in visual isolation for the *lockscreen* NowBar media card.
 *
 * <p>Unlike shade media, NowBarMediaCardView is supplied by the AOD plugin and contains two
 * separate CardView paints. This probe hides only the CardViews' background drawables, preserving
 * the ViewGroups, circular album artwork, controllers and WaveView. It is not the glass feature:
 * it only identifies which native paint obscures a future glass layer. Modes 1 and 2 isolate the
 * inner and outer backgrounds separately; mode 3 hides both. Zero or an unset property restores
 * all native drawable alphas. The existing one-second LiveMediaSurvey scheduler calls this only
 * while explicitly enabled; no per-frame or hot-path hooks are added.</p>
 */
final class LockMediaProbe {
    static final String PROPERTY = "debug.oulg.lockmediaprobe";
    static final String MEDIA_CLASS = "com.samsung.android.app.facewidget.presentation.NowBarMediaCardView";
    private static final String COMPACT_INNER = "stack_background_cardview";
    private static final String COMPACT_OUTER = "nowbar_cardview_background";
    private static final String EXPANDED_INNER = "nowbar_expandview_background";
    private static final String EXPANDED_OUTER = "expand_content_rootview";
    private static final int MAX_NODES = 140;
    private static final int MAX_DEPTH = 20;
    private static final WeakHashMap<View, Session> sessions = new WeakHashMap<>();
    private static Method propertyGet;
    private static int scan;

    private LockMediaProbe() { }

    static int mode() {
        try {
            if (propertyGet == null)
                propertyGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            String value = (String) propertyGet.invoke(null, PROPERTY);
            return "1".equals(value) ? 1 : "2".equals(value) ? 2 : "3".equals(value) ? 3 : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    static boolean hasSessions() { return !sessions.isEmpty(); }

    static boolean isLockMedia(View view) {
        return view != null && MEDIA_CLASS.equals(view.getClass().getName());
    }

    static void beginScan() { scan++; }

    static void observe(View root, int mode, String window) {
        if (mode == 0 || !isLockMedia(root)) return;
        Session state = sessions.get(root);
        if (state == null) {
            state = new Session();
            sessions.put(root, state);
        }
        state.seen = scan;

        // Capture the exact views, not a guessed path. Expanded and compact subtrees coexist;
        // Samsung switches their visibility and geometry during expansion.
        Map<String, View> found = new HashMap<>(4);
        IdentityHashMap<Drawable, Integer> refs = new IdentityHashMap<>();
        find(root, found, refs, new int[] {MAX_NODES}, 0);
        boolean expanded = root.getHeight() > root.getWidth();
        String inner = expanded ? EXPANDED_INNER : COMPACT_INNER;
        String outer = expanded ? EXPANDED_OUTER : COMPACT_OUTER;
        boolean changed = state.mode != mode || state.expanded != expanded;

        // A lease is tied to its exact Drawable instance. Samsung can replace a background
        // during a transition; release the old instance and take a fresh native snapshot.
        for (Iterator<Map.Entry<String, Lease>> it = state.leases.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Lease> entry = it.next();
            String name = entry.getKey();
            View view = found.get(name);
            boolean desired = (name.equals(inner) && hideInner(mode))
                    || (name.equals(outer) && hideOuter(mode));
            if (!desired || view == null || !entry.getValue().matches(view)) {
                entry.getValue().restore();
                it.remove();
            }
        }

        for (String name : new String[] {inner, outer}) {
            if (!(name.equals(inner) ? hideInner(mode) : hideOuter(mode))) continue;
            View view = found.get(name);
            if (view == null) continue;
            Drawable d = view.getBackground();
            // A shared Drawable could also draw elsewhere; leave it untouched rather than
            // hiding a sibling, artwork, or controls indirectly.
            if (d == null || refs.getOrDefault(d, 0) != 1) continue;
            Lease lease = state.leases.get(name);
            if (lease == null) {
                lease = new Lease(view);
                if (!lease.valid()) continue;
                if (!lease.hide()) continue;
                state.leases.put(name, lease);
                Probe.log("LOCK_MEDIA_PROBE_LAYER", "action=hide mode=" + mode + " layout="
                        + (expanded ? "expanded" : "compact") + " target=" + name
                        + " drawable=" + d.getClass().getName() + " nativeAlpha=" + lease.nativeAlpha()
                        + " readbackAlpha=" + d.getAlpha());
            } else {
                lease.hide();
            }
        }

        if (changed) {
            state.mode = mode;
            state.expanded = expanded;
            Probe.log("LOCK_MEDIA_PROBE", "state=active mode=" + mode
                    + " layout=" + (expanded ? "expanded" : "compact")
                    + " root=" + Integer.toHexString(System.identityHashCode(root))
                    + " window=" + window + " hidden=" + state.leases.keySet()
                    + " retainedArt=true retainedControls=true retainedWave=true");
        }
    }

    static void endScan(int mode) {
        for (Iterator<Map.Entry<View, Session>> it = sessions.entrySet().iterator(); it.hasNext();) {
            Map.Entry<View, Session> entry = it.next();
            Session s = entry.getValue();
            if (mode > 0 && s.seen == scan) continue;
            for (Lease lease : s.leases.values()) lease.restore();
            s.leases.clear();
            Probe.log("LOCK_MEDIA_PROBE", "state=restored reason=" + (mode == 0 ? "propertyOff" : "detached")
                    + " root=" + Integer.toHexString(System.identityHashCode(entry.getKey())));
            it.remove();
        }
    }

    static boolean hideInner(int mode) { return mode == 1 || mode == 3; }
    static boolean hideOuter(int mode) { return mode == 2 || mode == 3; }

    private static void find(View v, Map<String, View> found,
                             IdentityHashMap<Drawable, Integer> refs, int[] budget, int depth) {
        if (v == null || depth > MAX_DEPTH || budget[0]-- <= 0) return;
        Drawable bg = v.getBackground();
        if (bg != null) refs.put(bg, refs.getOrDefault(bg, 0) + 1);
        String name = LiveStackExternalProbe.resourceName(v);
        if (COMPACT_INNER.equals(name) || COMPACT_OUTER.equals(name)
                || EXPANDED_INNER.equals(name) || EXPANDED_OUTER.equals(name)) found.put(name, v);
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++)
            find(g.getChildAt(i), found, refs, budget, depth + 1);
    }

    private static final class Session {
        int seen;
        int mode;
        boolean expanded;
        final Map<String, Lease> leases = new HashMap<>();
    }

    /** A reversible change to one Drawable; never touches the owning View's alpha or children. */
    static final class Lease {
        private final WeakReference<View> view;
        private final WeakReference<Drawable> drawable;
        private int nativeAlpha;
        private boolean hidden;
        private boolean failed;

        Lease(View view) {
            this.view = new WeakReference<>(view);
            Drawable bg = view == null ? null : view.getBackground();
            this.drawable = new WeakReference<>(bg);
            this.nativeAlpha = bg == null ? 255 : bg.getAlpha();
        }

        boolean valid() { return drawable.get() != null; }
        int nativeAlpha() { return nativeAlpha; }

        boolean matches(View candidate) {
            return view.get() == candidate && drawable.get() != null
                    && candidate.getBackground() == drawable.get();
        }

        boolean hide() {
            Drawable d = drawable.get();
            if (d == null || failed) return false;
            // The same instance may be written by Samsung after our previous tick.
            // Suppress it again without emitting another log or allocating per frame.
            try {
                int current = d.getAlpha();
                if (hidden && current != 0) nativeAlpha = current;
                if (current != 0 || !hidden) d.setAlpha(0);
                hidden = true;
                return true;
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=hide type=" + e.getClass().getSimpleName());
                return false;
            }
        }

        void restore() {
            Drawable d = drawable.get();
            if (d != null && hidden) {
                try { d.setAlpha(nativeAlpha); }
                catch (RuntimeException | LinkageError e) {
                    Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=restore type=" + e.getClass().getSimpleName());
                }
            }
            hidden = false;
        }
    }
}

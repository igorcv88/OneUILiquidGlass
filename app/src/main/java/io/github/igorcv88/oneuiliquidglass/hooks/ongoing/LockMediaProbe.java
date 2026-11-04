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
 * separate CardView paints. Only the compact capsule is probed: changing expanded-card paints
 * was observed to expose an intermittent black fallback in the AOD plugin. Samsung's aod.m96
 * drawable ignored setAlpha(0) (readback remained 255), so a reversible background removal is
 * used instead. The original Drawable object is kept intact and restored only if Samsung has not
 * installed a newer background. Artwork, controllers and WaveView are untouched. Modes 1 and 2
 * isolate inner and outer compact backgrounds; mode 3 removes both. The existing one-second
 * LiveMediaSurvey scheduler calls this only while enabled; there are no per-frame hooks.
 * Additional modes 4-7 isolate a foreground and decoration-only views after background
 * removal was confirmed on-device but produced no visual difference.</p>
 */
final class LockMediaProbe {
    static final String PROPERTY = "debug.oulg.lockmediaprobe";
    static final String MEDIA_CLASS = "com.samsung.android.app.facewidget.presentation.NowBarMediaCardView";
    private static final String COMPACT_INNER = "stack_background_cardview";
    private static final String COMPACT_OUTER = "nowbar_cardview_background";
    private static final String SHADER_PARENT = "background_shader_parent";
    private static final String BORDER_STROKE = "nowbar_card_border_stroke_view";
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
            if (value != null && value.length() == 1 && value.charAt(0) >= '1' && value.charAt(0) <= '7')
                return value.charAt(0) - '0';
            return 0;
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
        String inner = COMPACT_INNER;
        String outer = COMPACT_OUTER;
        boolean changed = state.mode != mode || state.expanded != expanded;

        // A lease is tied to its exact Drawable instance. Samsung can replace a background
        // during a transition; release the old instance and take a fresh native snapshot.
        for (Iterator<Map.Entry<String, Lease>> it = state.leases.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Lease> entry = it.next();
            String name = entry.getKey();
            View view = found.get(name);
            boolean desired = shouldProbe(expanded, mode)
                    && ((name.equals(inner) && hideInner(mode))
                    || (name.equals(outer) && hideOuter(mode)));
            if (!desired || view == null || !entry.getValue().matches(view)) {
                entry.getValue().restore();
                it.remove();
            }
        }

        for (String name : new String[] {inner, outer}) {
            if (!shouldProbe(expanded, mode)
                    || !(name.equals(inner) ? hideInner(mode) : hideOuter(mode))) continue;
            View view = found.get(name);
            if (view == null) continue;
            Lease lease = state.leases.get(name);
            if (lease == null) {
                Drawable d = view.getBackground();
                // Do not remove a drawable that is referenced by another view.
                if (d == null || refs.getOrDefault(d, 0) != 1) continue;
                lease = new Lease(view);
                if (!lease.valid()) continue;
                if (!lease.hide()) continue;
                state.leases.put(name, lease);
                Probe.log("LOCK_MEDIA_PROBE_LAYER", "action=remove mode=" + mode + " layout=compact target=" + name
                        + " drawable=" + d.getClass().getName() + " nativeAlpha=" + lease.nativeAlpha()
                        + " backgroundRemoved=" + (view.getBackground() == null));
            } else {
                lease.hide();
            }
        }

        // Modes 4-7 isolate draws that the old media survey found *in addition* to CardView
        // backgrounds. These views contain no album art, waveform, text or player controls.
        state.foreground = updateForeground(state.foreground, found.get(COMPACT_INNER), refs,
                shouldProbe(expanded, mode) && mode == 4, mode);
        state.decoration = updateVisibility(state.decoration, found.get(COMPACT_INNER),
                shouldProbe(expanded, mode) && mode >= 5, mode, "compactDecoration");
        state.shader = updateVisibility(state.shader, found.get(SHADER_PARENT),
                shouldProbe(expanded, mode) && mode == 7, mode, "shaderParent");
        state.stroke = updateVisibility(state.stroke, found.get(BORDER_STROKE),
                shouldProbe(expanded, mode) && mode == 7, mode, "borderStroke");

        if (changed) {
            state.mode = mode;
            state.expanded = expanded;
            Probe.log("LOCK_MEDIA_PROBE", "state=active mode=" + mode
                    + " layout=" + (expanded ? "expanded" : "compact")
                    + " root=" + Integer.toHexString(System.identityHashCode(root))
                    + " window=" + window + " removed=" + state.leases.keySet()
                    + " foregroundRemoved=" + (state.foreground != null)
                    + " decorationHidden=" + (state.decoration != null)
                    + " shaderHidden=" + (state.shader != null)
                    + " strokeHidden=" + (state.stroke != null)
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
            if (s.foreground != null) s.foreground.restore();
            if (s.decoration != null) s.decoration.restore();
            if (s.shader != null) s.shader.restore();
            if (s.stroke != null) s.stroke.restore();
            Probe.log("LOCK_MEDIA_PROBE", "state=restored reason=" + (mode == 0 ? "propertyOff" : "detached")
                    + " root=" + Integer.toHexString(System.identityHashCode(entry.getKey())));
            it.remove();
        }
    }

    static boolean hideInner(int mode) { return mode == 1 || mode == 3; }
    static boolean hideOuter(int mode) { return mode == 2 || mode == 3 || mode == 6 || mode == 7; }
    static boolean shouldProbe(boolean expanded, int mode) { return !expanded && mode != 0; }

    private static void find(View v, Map<String, View> found,
                             IdentityHashMap<Drawable, Integer> refs, int[] budget, int depth) {
        if (v == null || depth > MAX_DEPTH || budget[0]-- <= 0) return;
        Drawable bg = v.getBackground();
        if (bg != null) refs.put(bg, refs.getOrDefault(bg, 0) + 1);
        Drawable fg = v.getForeground();
        if (fg != null) refs.put(fg, refs.getOrDefault(fg, 0) + 1);
        String name = LiveStackExternalProbe.resourceName(v);
        if (COMPACT_INNER.equals(name) || COMPACT_OUTER.equals(name)
                || SHADER_PARENT.equals(name) || BORDER_STROKE.equals(name)) found.put(name, v);
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
        ForegroundLease foreground;
        VisibilityLease decoration;
        VisibilityLease shader;
        VisibilityLease stroke;
    }

    private static ForegroundLease updateForeground(ForegroundLease lease, View view,
                                                   IdentityHashMap<Drawable, Integer> refs,
                                                   boolean desired, int mode) {
        if (lease != null && (!desired || !lease.matches(view))) {
            lease.restore();
            lease = null;
        }
        if (!desired || view == null || lease != null) return lease;
        Drawable d = view.getForeground();
        if (d == null || refs.getOrDefault(d, 0) != 1) return null;
        ForegroundLease next = new ForegroundLease(view);
        if (!next.hide()) return null;
        Probe.log("LOCK_MEDIA_PROBE_LAYER", "action=foreground-remove mode=" + mode
                + " target=" + COMPACT_INNER + " drawable=" + d.getClass().getName()
                + " foregroundRemoved=" + (view.getForeground() == null));
        return next;
    }

    private static VisibilityLease updateVisibility(VisibilityLease lease, View view,
                                                    boolean desired, int mode, String label) {
        if (lease != null && (!desired || !lease.matches(view))) {
            lease.restore();
            lease = null;
        }
        if (!desired || view == null || lease != null) return lease;
        VisibilityLease next = new VisibilityLease(view);
        if (!next.hide()) return null;
        Probe.log("LOCK_MEDIA_PROBE_LAYER", "action=decoration-hide mode=" + mode
                + " target=" + label + " visibility=" + view.getVisibility());
        return next;
    }

    /** Reversible foreground-only removal for the empty compact decoration CardView. */
    static final class ForegroundLease {
        private final WeakReference<View> view;
        private final Drawable foreground;
        private boolean hidden;

        ForegroundLease(View target) {
            view = new WeakReference<>(target);
            foreground = target == null ? null : target.getForeground();
        }

        boolean matches(View target) {
            return target != null && view.get() == target && foreground != null
                    && target.getForeground() == null;
        }

        boolean hide() {
            View target = view.get();
            if (target == null || foreground == null || target.getForeground() != foreground) return false;
            try {
                target.setForeground(null);
                hidden = target.getForeground() == null;
                return hidden;
            } catch (RuntimeException | LinkageError e) {
                if (target.getForeground() == null) {
                    try { target.setForeground(foreground); } catch (RuntimeException | LinkageError ignored) { }
                }
                Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=foreground-remove type="
                        + e.getClass().getSimpleName());
                return false;
            }
        }

        void restore() {
            View target = view.get();
            if (hidden && target != null && target.getForeground() == null) {
                try { target.setForeground(foreground); }
                catch (RuntimeException | LinkageError e) {
                    Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=foreground-restore type="
                            + e.getClass().getSimpleName());
                }
            }
            hidden = false;
        }
    }

    /** Hide an empty decorative node without altering its layout slot or any native paints. */
    static final class VisibilityLease {
        private final WeakReference<View> view;
        private final int nativeVisibility;
        private boolean hidden;

        VisibilityLease(View target) {
            view = new WeakReference<>(target);
            nativeVisibility = target == null ? View.GONE : target.getVisibility();
        }

        boolean matches(View target) {
            return target != null && view.get() == target
                    && target.getVisibility() == View.INVISIBLE;
        }

        boolean hide() {
            View target = view.get();
            if (target == null || nativeVisibility != View.VISIBLE) return false;
            try {
                target.setVisibility(View.INVISIBLE);
                hidden = target.getVisibility() == View.INVISIBLE;
                return hidden;
            } catch (RuntimeException | LinkageError e) {
                Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=decoration-hide type="
                        + e.getClass().getSimpleName());
                return false;
            }
        }

        void restore() {
            View target = view.get();
            if (hidden && target != null && target.getVisibility() == View.INVISIBLE) {
                try { target.setVisibility(nativeVisibility); }
                catch (RuntimeException | LinkageError e) {
                    Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=decoration-restore type="
                            + e.getClass().getSimpleName());
                }
            }
            hidden = false;
        }
    }

    /** A reversible background removal; never changes alpha on the View or native Drawable. */
    static final class Lease {
        private final WeakReference<View> view;
        // Strongly hold the removed native object so it remains restorable after GC.
        private final Drawable drawable;
        private final int nativeAlpha;
        private boolean hidden;
        private boolean failed;

        Lease(View view) {
            this.view = new WeakReference<>(view);
            Drawable bg = view == null ? null : view.getBackground();
            this.drawable = bg;
            this.nativeAlpha = bg == null ? 255 : bg.getAlpha();
        }

        boolean valid() { return drawable != null; }
        int nativeAlpha() { return nativeAlpha; }

        boolean matches(View candidate) {
            // Native updates during the probe are retained rather than overwritten.
            return view.get() == candidate && drawable != null
                    && candidate.getBackground() == null;
        }

        boolean hide() {
            View v = view.get();
            if (v == null || drawable == null || failed) return false;
            if (hidden) return v.getBackground() == null;
            if (v.getBackground() != drawable) return false;
            try {
                v.setBackground(null);
                hidden = v.getBackground() == null;
                if (!hidden) {
                    failed = true;
                    Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=remove-rejected background="
                            + v.getBackground().getClass().getName());
                }
                return hidden;
            } catch (RuntimeException | LinkageError e) {
                failed = true;
                if (v.getBackground() == null) {
                    try { v.setBackground(drawable); } catch (RuntimeException | LinkageError ignored) { }
                }
                Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=remove type=" + e.getClass().getSimpleName());
                return false;
            }
        }

        void restore() {
            View v = view.get();
            if (v != null && drawable != null && hidden && v.getBackground() == null) {
                try { v.setBackground(drawable); }
                catch (RuntimeException | LinkageError e) {
                    Probe.log("LOCK_MEDIA_PROBE_ERROR", "action=restore type=" + e.getClass().getSimpleName());
                }
            }
            hidden = false;
        }
    }
}

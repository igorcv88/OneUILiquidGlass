package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.SemBlurBridge;

/**
 * Lifecycle of the glass on the hosts the ongoing hooks classified. Main thread only.
 *
 * <p>A host is <i>offered</i> by its owner hook with a verdict. Eligible and allowed by its gate
 * (master switch, its own opt-in, no persistent failure): the background Samsung wrote is
 * wrapped in a {@link LiveGlassDrawable} inside the same call that classified it (bind, factory,
 * writer), so the first frame already draws it. A later native writer replaces the background;
 * the owner offers the host again and the new drawable becomes the wrapped native one. Not
 * eligible any more (recycled for media, data changed), gate off or detach of a dead toast: the
 * exact native background and Samsung's own blur are put back.</p>
 *
 * <p>The compositor region exists only while the host is attached and cross-window blur is on;
 * otherwise the wrapper draws the native drawable, so a translucent pane never shows over a sharp
 * backdrop. A pre-draw listener, only while attached, makes the region follow ancestor alpha
 * (native fades) without invalidating anything.</p>
 */
public final class LiveGlass {
    /** The compositor region for a host, and whether cross-window blur is on now. */
    public interface Blur {
        BackgroundBlurBridge create(View host) throws ReflectiveOperationException;
        boolean enabled(View host);
    }
    /** Master switch, opt-in and failure budget of a surface. */
    public interface Gate { boolean allows(LiveSurface surface); }
    /** Surface policy for the region: false while it must stay native (Now Bar in doze). */
    public interface Policy { boolean glassAllowed(LiveSurface surface, View host); }

    /** Host-specific rules of an offer (Now Bar's layered card); the defaults suit a plain host. */
    public static final class Options {
        /** The host may have no native background (a blur layer): native then draws nothing. */
        public boolean allowEmptyNative;
        /** Called when the drawn layer flips between glass and native. */
        public java.util.function.BiConsumer<View, Boolean> onShown;
        /** Radii and colour come from this view (the CardView above a blur layer) instead of the host. */
        public View shapeSource;
    }
    private static final Options PLAIN = new Options();

    /** What happened to an offer, for tests and the trace. */
    public enum Outcome { APPLIED, REASSERTED, INTACT, CANDIDATE, RESTORED, NATIVE, DENIED }

    static final class State {
        final LiveSurface surface;
        final WeakReference<View> host;
        WeakReference<Object> owner = new WeakReference<>(null);
        Predicate<Object> recheck;
        LiveGlassDrawable glass;
        /** Samsung's own blur, removed while the glass is on and re-installed on restore. */
        Object semInfo;
        boolean allowEmptyNative;
        /** Called when the drawn layer flips between glass and native (Now Bar companions). */
        java.util.function.BiConsumer<View, Boolean> onShown;
        WeakReference<View> shapeSource = new WeakReference<>(null);
        /** The region failed while drawing: no new one for this wrapper (no rebuild loop). */
        boolean regionBroken;
        boolean lastShown;
        View.OnAttachStateChangeListener attach;
        ViewTreeObserver.OnPreDrawListener preDraw;
        ViewTreeObserver observer;
        State(LiveSurface surface, View host) { this.surface = surface; this.host = new WeakReference<>(host); }
    }

    private final WeakHashMap<View, State> states = new WeakHashMap<>();
    private final Blur blur;
    private final Gate gate;
    private final Policy policy;
    private final Map<LiveSurface, Integer> logged = new EnumMap<>(LiveSurface.class);
    /** True while this controller itself writes a background (no owner hook may react to it). */
    private boolean writing;

    public LiveGlass(Blur blur, Gate gate, Policy policy) { this.blur = blur; this.gate = gate; this.policy = policy; }

    public boolean writing() { return writing; }
    public boolean tracked(View v) { return states.containsKey(v); }
    public boolean applied(View v) { State s = states.get(v); return s != null && s.glass != null; }
    public LiveGlassDrawable glassOf(View v) { State s = states.get(v); return s == null ? null : s.glass; }
    public Object ownerOf(View v) { State s = states.get(v); return s == null ? null : s.owner.get(); }
    public LiveSurface surfaceOf(View v) { State s = states.get(v); return s == null ? null : s.surface; }
    /** Tracked hosts of one surface (a snapshot; hosts may be collected meanwhile). */
    public List<View> hosts(LiveSurface surface) {
        List<View> out = new ArrayList<>();
        for (Map.Entry<View, State> e : new ArrayList<>(states.entrySet()))
            if (e.getValue().surface == surface && e.getKey() != null) out.add(e.getKey());
        return out;
    }

    /** Offer with the default empty-native rule (a host without native background stays native). */
    public Outcome offer(View host, LiveSurface surface, Object owner, LiveEligibility.Verdict verdict, Predicate<Object> recheck) {
        return offer(host, surface, owner, verdict, recheck, PLAIN);
    }

    /**
     * Classifies {@code host} for {@code owner}. {@code recheck} re-reads the owner's own flags
     * (media may flip in place) when the gate changes or a writer fires outside a bind.
     */
    public Outcome offer(View host, LiveSurface surface, Object owner, LiveEligibility.Verdict verdict, Predicate<Object> recheck,
                         Options options) {
        if (host == null) return Outcome.DENIED;
        State s = states.get(host);
        if (!verdict.ok()) {
            if (s == null) return Outcome.DENIED;
            boolean had = s.glass != null;
            forget(host, "verdict=" + verdict.name());
            return had ? Outcome.RESTORED : Outcome.DENIED;
        }
        if (s == null || s.surface != surface) {
            if (s != null) forget(host, "surface");
            s = new State(surface, host);
            states.put(host, s);
        }
        s.owner = new WeakReference<>(owner);
        s.recheck = recheck;
        s.allowEmptyNative = options.allowEmptyNative;
        s.onShown = options.onShown;
        s.shapeSource = new WeakReference<>(options.shapeSource);
        if (!gate.allows(surface)) {
            if (s.glass != null) { restore(s, "gate"); return Outcome.RESTORED; }
            return Outcome.CANDIDATE;
        }
        return s.glass == null ? apply(s) : reassert(s);
    }

    /** A native writer ran on a host outside a bind: re-check the stored owner and reassert. */
    public Outcome rewrite(View host) {
        State s = states.get(host);
        if (s == null) return Outcome.DENIED;
        Object owner = s.owner.get();
        if (owner == null || s.recheck == null || !s.recheck.test(owner)) { forget(host, "recheck"); return Outcome.RESTORED; }
        if (!gate.allows(s.surface)) return s.glass == null ? Outcome.CANDIDATE : restoreOutcome(s);
        return s.glass == null ? apply(s) : reassert(s);
    }
    private Outcome restoreOutcome(State s) { restore(s, "gate"); return Outcome.RESTORED; }

    /** Gate changed (config, failure): apply or restore every host of the surface. */
    public void refresh(LiveSurface surface) {
        boolean on = gate.allows(surface);
        int applied = 0, restored = 0;
        for (Map.Entry<View, State> e : new ArrayList<>(states.entrySet())) {
            State s = e.getValue();
            if (s.surface != surface || e.getKey() == null) continue;
            if (!on) { if (s.glass != null) { restore(s, "gate"); restored++; } continue; }
            Object owner = s.owner.get();
            if (owner == null || s.recheck == null || !s.recheck.test(owner)) { forget(e.getKey(), "recheck"); restored++; continue; }
            Outcome o = s.glass == null ? apply(s) : reassert(s);
            if (o == Outcome.APPLIED) applied++;
        }
        Probe.log("LIVE_REFRESH", "surface=" + surface + " on=" + on + " applied=" + applied + " restored=" + restored);
    }

    /** Restores the native background (if applied) and stops tracking the host. */
    public void forget(View host, String reason) {
        State s = states.remove(host);
        if (s != null && s.glass != null) restore(s, reason);
        else if (s != null) removeListeners(s);
    }
    public void restoreAll(LiveSurface surface, String reason) {
        for (Map.Entry<View, State> e : new ArrayList<>(states.entrySet()))
            if (e.getValue().surface == surface && e.getValue().glass != null) restore(e.getValue(), reason);
    }

    /** Cross-window blur or display state changed: rebuild or drop the region of every host. */
    public void syncAll() {
        for (State s : new ArrayList<>(states.values())) if (s.glass != null) sync(s);
    }

    // ---- internals -------------------------------------------------------------------------

    /** The drawable semSetBlurInfo installs: a blur region, not a fill that could be wrapped. */
    private static boolean blurDrawable(Drawable d) {
        return d != null && !(d instanceof LiveGlassDrawable) && d.getClass().getName().contains("BlurDrawable");
    }

    private Outcome apply(State s) {
        View v = s.host.get();
        if (v == null) return Outcome.DENIED;
        Drawable bg = v.getBackground();
        if (bg instanceof LiveGlassDrawable) return Outcome.INTACT;
        // Samsung's own blur on the host (setBlurBG, a.N), wherever One UI draws it: put aside.
        Object sem = SemBlurBridge.heldInfo(v);
        if (blurDrawable(bg)) bg = null;
        if (bg == null && !s.allowEmptyNative) return nativeOutcome(s, "noBackground");
        if (bg != null && NativeShape.interactive(bg)) return nativeOutcome(s, "ripple");
        if (bg != null && NativeShape.cardLike(v)) return nativeOutcome(s, "cardview");
        View source = s.shapeSource.get();
        float[] radii = source != null ? NativeShape.radii(source.getBackground(), source) : NativeShape.radii(bg, v);
        if (radii == null) return nativeOutcome(s, "noRadius");
        Integer color = source != null ? NativeShape.color(source.getBackground(), source) : bg == null ? null : NativeShape.color(bg, v);
        LiveGlassDrawable glass = new LiveGlassDrawable(bg, radii, color, s.surface.spec, v.getResources().getDisplayMetrics().density);
        if (sem != null) SemBlurBridge.clearNative(v);
        writing = true;
        try {
            v.setBackground(glass);
            glass.setNative(bg);
        } finally { writing = false; }
        s.glass = glass;
        s.semInfo = sem;
        s.lastShown = false;
        s.regionBroken = false;
        glass.setOnRegionFailed(() -> regionFailed(s));
        listen(s, v);
        if (v.isAttachedToWindow()) sync(s);
        logOnce(s.surface, "LIVE_GLASS_APPLY", "surface=" + s.surface + " host=" + v.getClass().getName()
                + " native=" + (bg == null ? "none" : NativeShape.unwrap(bg).getClass().getSimpleName()) + " sem=" + (sem != null)
                + " radius=" + radii[0] + " attached=" + v.isAttachedToWindow());
        return Outcome.APPLIED;
    }
    private Outcome nativeOutcome(State s, String reason) {
        logOnce(s.surface, "LIVE_GLASS_NATIVE", "surface=" + s.surface + " reason=" + reason);
        return Outcome.NATIVE;
    }

    private Outcome reassert(State s) {
        View v = s.host.get();
        if (v == null) return Outcome.DENIED;
        Drawable bg = v.getBackground();
        Object held = SemBlurBridge.heldInfo(v);
        if (held != null) {
            // Samsung re-installed its own blur (setBlurBG, a.N): it leaves while the glass is on.
            s.semInfo = held;
            SemBlurBridge.clearNative(v);
            bg = v.getBackground();
            if (bg == s.glass) return Outcome.REASSERTED;
            if (bg == null || blurDrawable(bg)) bg = s.glass.nativeDrawable();
        } else if (bg == s.glass) {
            return Outcome.INTACT;
        } else if (blurDrawable(bg)) {
            bg = s.glass.nativeDrawable();
        } else if (bg == null && !s.allowEmptyNative) {
            // Samsung removed the background on purpose: the glass would add a pane it does not want.
            LiveGlassDrawable g = s.glass;
            s.glass = null;
            g.release();
            removeListeners(s);
            if (s.lastShown && s.onShown != null) { s.lastShown = false; s.onShown.accept(v, false); }
            if (s.semInfo != null) { SemBlurBridge.setInfo(v, s.semInfo); s.semInfo = null; }
            return Outcome.NATIVE;
        } else if (bg instanceof LiveGlassDrawable) {
            bg = ((LiveGlassDrawable) bg).nativeDrawable();
        }
        if (bg != null && (NativeShape.interactive(bg) || NativeShape.cardLike(v))) { forget(v, "nativeChanged"); return Outcome.RESTORED; }
        View source = s.shapeSource.get();
        float[] radii = source != null ? NativeShape.radii(source.getBackground(), source) : bg == null ? null : NativeShape.radii(bg, v);
        if (radii != null) s.glass.setRadii(radii);
        s.glass.setNativeColor(source != null ? NativeShape.color(source.getBackground(), source) : bg == null ? null : NativeShape.color(bg, v));
        writing = true;
        try {
            v.setBackground(s.glass);
            s.glass.setNative(bg);
        } finally { writing = false; }
        return Outcome.REASSERTED;
    }

    private void restore(State s, String reason) {
        View v = s.host.get();
        LiveGlassDrawable g = s.glass;
        s.glass = null;
        removeListeners(s);
        if (g == null) return;
        Drawable nativeBg = g.nativeDrawable();
        g.release();
        if (v == null) return;
        if (s.lastShown && s.onShown != null) { s.lastShown = false; s.onShown.accept(v, false); }
        writing = true;
        try {
            // Only our wrapper is replaced: a background Samsung wrote meanwhile stays.
            if (v.getBackground() == g) v.setBackground(nativeBg);
        } finally { writing = false; }
        if (s.semInfo != null) { SemBlurBridge.setInfo(v, s.semInfo); s.semInfo = null; }
        Probe.log("LIVE_GLASS_RESTORE", "surface=" + s.surface + " reason=" + reason + " host=" + v.getClass().getSimpleName());
    }

    private void listen(State s, View v) {
        if (s.attach != null) return;
        s.attach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { sync(s); }
            @Override public void onViewDetachedFromWindow(View view) {
                if (s.glass != null) { s.glass.setBlur(null); shown(s, view, false); }
                removePreDraw(s);
                // A toast view is never shown again: put its background back and drop it.
                if (s.surface == LiveSurface.TOAST) forget(view, "detached");
            }
        };
        v.addOnAttachStateChangeListener(s.attach);
    }
    private void removeListeners(State s) {
        View v = s.host.get();
        removePreDraw(s);
        if (v != null && s.attach != null) v.removeOnAttachStateChangeListener(s.attach);
        s.attach = null;
    }
    private void removePreDraw(State s) {
        if (s.observer != null && s.preDraw != null && s.observer.isAlive()) s.observer.removeOnPreDrawListener(s.preDraw);
        s.observer = null;
        s.preDraw = null;
    }

    /** Builds or drops the region for the current blur capability and surface policy. */
    void sync(State s) {
        View v = s.host.get();
        LiveGlassDrawable g = s.glass;
        if (v == null || g == null) return;
        boolean want = !s.regionBroken && v.isAttachedToWindow() && blur.enabled(v) && policy.glassAllowed(s.surface, v);
        if (want && g.blur() == null) {
            try { g.setBlur(blur.create(v)); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                logOnce(s.surface, "LIVE_GLASS_NO_REGION", "surface=" + s.surface + " error=" + e.getClass().getSimpleName());
                g.setBlur(null);
            }
        } else if (!want && g.blur() != null) g.setBlur(null);
        g.setGlass(want);
        shown(s, v, g.showsGlass());
        if (v.isAttachedToWindow() && s.preDraw == null) {
            ViewTreeObserver o = v.getViewTreeObserver();
            s.preDraw = () -> { fade(s); return true; };
            s.observer = o;
            o.addOnPreDrawListener(s.preDraw);
        }
        fade(s);
        v.invalidate();
    }
    /** Mid-draw failure of the region: native at once, companions (CardView, gradient) restored. */
    private void regionFailed(State s) {
        s.regionBroken = true;
        View v = s.host.get();
        if (v == null || s.glass == null) return;
        s.glass.setGlass(false);
        shown(s, v, false);
        logOnce(s.surface, "LIVE_GLASS_NO_REGION", "surface=" + s.surface + " stage=draw");
    }
    private void shown(State s, View v, boolean on) {
        if (s.lastShown == on) return;
        s.lastShown = on;
        if (s.onShown != null) s.onShown.accept(v, on);
    }
    private void fade(State s) {
        View v = s.host.get();
        if (v == null || s.glass == null) return;
        s.glass.setRegionFade(accumulatedAlpha(v));
    }
    /** Alpha of the host and its ancestors; 0 if any of them is not visible. */
    public static float accumulatedAlpha(View v) {
        float a = 1f;
        Object p = v;
        for (int i = 0; i < 64 && p instanceof View; i++) {
            View x = (View) p;
            if (x.getVisibility() != View.VISIBLE) return 0f;
            a *= x.getAlpha();
            p = x.getParent();
        }
        return a;
    }
    private void logOnce(LiveSurface surface, String event, String detail) {
        int n = logged.getOrDefault(surface, 0);
        if (n >= 12) return;
        logged.put(surface, n + 1);
        Probe.log(event, detail);
    }
}

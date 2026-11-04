package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.diagnostics.LiveRenderProbe;
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
 * eligible any more (recycled for an unknown item, data changed), gate off or detach of a dead toast: the
 * exact native background and Samsung's own blur are put back.</p>
 *
 * <p>The compositor region exists only while the host is attached and cross-window blur is on;
 * otherwise the wrapper draws the native drawable, so a translucent pane never shows over a sharp
 * backdrop. An attached pre-draw listener reconciles background ownership and companion layers, and
 * makes the region follow ancestor alpha without rebuilding it on unchanged frames.</p>
 */
public final class LiveGlass {
    /** The compositor region for a host, and whether cross-window blur is on now. */
    public interface Blur {
        BackgroundBlurBridge create(View host) throws ReflectiveOperationException;
        boolean enabled(View host);
        default BackgroundBlurBridge create(View host, LiveSurface surface) throws ReflectiveOperationException { return create(host); }
    }
    /** Samsung's own SemBlurInfo on a host (read, clear, re-install); production: SemBlurBridge. */
    public interface NativeBlur {
        Object held(View host);
        void clear(View host);
        void set(View host, Object info);
    }
    static final NativeBlur SAMSUNG = new NativeBlur() {
        @Override public Object held(View host) { return SemBlurBridge.heldInfo(host); }
        @Override public void clear(View host) { SemBlurBridge.clearNative(host); }
        @Override public void set(View host, Object info) { SemBlurBridge.setInfo(host, info); }
    };
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
        /**
         * True while a native blur layer already sits behind the host (L2: Samsung's
         * BackgroundBlurDrawable on an ancestor of the card fill). The glass then adds no region of
         * its own, only makes the native fill translucent; re-evaluated on every offer and sync.
         */
        public Predicate<View> nativeRegion;
        /** Reconciles background-only companions after the mode decision, including native rewrites. */
        public java.util.function.BiConsumer<View, Boolean> onSync;
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
        Predicate<View> nativeRegion;
        java.util.function.BiConsumer<View, Boolean> onSync;
        /** The region failed while drawing: no new one for this wrapper (no rebuild loop). */
        boolean regionBroken;
        /**
         * Host with Samsung's own blur, kept native until the compositor region can exist: it is
         * wrapped only while the glass is really drawn, never left without both blurs.
         */
        boolean waiting;
        /** Last non-blur native background, kept across a return to Samsung's blur. */
        Drawable lastNative;
        Drawable displacedBackground;
        int backgroundRepairs;
        boolean lastShown;
        /** Policy/capability sampled by lifecycle events, never queried through Binder per frame. */
        boolean available;
        /** Per-host state-change telemetry; never holds the host or its notification data. */
        final LiveRenderProbe render = new LiveRenderProbe();
        View.OnAttachStateChangeListener attach;
        ViewTreeObserver.OnPreDrawListener preDraw;
        ViewTreeObserver observer;
        State(LiveSurface surface, View host) { this.surface = surface; this.host = new WeakReference<>(host); }
    }

    private final WeakHashMap<View, State> states = new WeakHashMap<>();
    private final Blur blur;
    private final Gate gate;
    private final Policy policy;
    /** True while this controller itself writes a background (no owner hook may react to it). */
    private boolean writing;

    private final NativeBlur nativeBlur;
    public LiveGlass(Blur blur, Gate gate, Policy policy) { this(blur, gate, policy, SAMSUNG); }
    public LiveGlass(Blur blur, Gate gate, Policy policy, NativeBlur nativeBlur) {
        this.blur = blur; this.gate = gate; this.policy = policy; this.nativeBlur = nativeBlur;
    }

    public boolean writing() { return writing; }
    /** The surface's gate now (master switch, its opt-in, failure budget). */
    public boolean allows(LiveSurface surface) { return gate.allows(surface); }
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
     * (automation flags may flip in place) when the gate changes or a writer fires outside a bind.
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
        s.nativeRegion = options.nativeRegion;
        s.onSync = options.onSync;
        if (!gate.allows(surface)) {
            if (s.glass != null) { restore(s, "gate"); return Outcome.RESTORED; }
            render(s, "GATED", "offer");
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
        for (State s : new ArrayList<>(states.values())) if (s.glass != null || s.waiting) sync(s);
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
        // Samsung's own blur on the host (setBlurBG, a.N), wherever One UI draws it.
        Object sem = nativeBlur.held(v);
        if (blurDrawable(bg)) bg = s.lastNative;
        if (bg == null && !s.allowEmptyNative) return nativeOutcome(s, "noBackground");
        if (bg != null && NativeShape.interactive(bg)) return nativeOutcome(s, "ripple");
        if (bg != null && NativeShape.cardLike(v)) return nativeOutcome(s, "cardview");
        View source = s.shapeSource.get();
        float[] radii = source != null ? NativeShape.radii(source.getBackground(), source) : NativeShape.radii(bg, v);
        if (radii == null) return nativeOutcome(s, "noRadius");
        Integer color = source != null ? NativeShape.color(source.getBackground(), source) : bg == null ? null : NativeShape.color(bg, v);
        BackgroundBlurBridge region = null;
        if (sem != null) {
            // Samsung's blur leaves only for a region that exists now: otherwise it stays, and the
            // host is wrapped on attach or when blur/display allow it (sync).
            s.waiting = true;
            listen(s, v);
            if (!canShow(s, v)) { render(s, "WAITING_SAMSUNG", "canShow=false"); return Outcome.NATIVE; }
            try { region = neutral(blur.create(v, s.surface)); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                s.regionBroken = true;
                logOnce(s.surface, "LIVE_GLASS_NO_REGION", "surface=" + s.surface + " stage=create keep=samsungBlur");
                render(s, "WAITING_SAMSUNG", "createFailed=" + e.getClass().getSimpleName());
                return Outcome.NATIVE;
            }
        }
        s.waiting = false;
        LiveGlassDrawable glass = new LiveGlassDrawable(bg, radii, color, s.surface.spec, v.getResources().getDisplayMetrics().density);
        glass.setRenderProbe(s.surface.name());
        if (sem != null) nativeBlur.clear(v);
        writing = true;
        try {
            v.setBackground(glass);
            glass.setNative(bg);
        } finally { writing = false; }
        if (region != null) glass.setBlur(region);
        s.glass = glass;
        s.semInfo = sem;
        if (bg != null) s.lastNative = bg;
        s.lastShown = false;
        s.regionBroken = false;
        glass.setOnRegionFailed(() -> regionFailed(s));
        glass.setNativeRegionActive(() -> {
            View host = s.host.get();
            return host != null && s.available && !s.regionBroken && host.isAttachedToWindow() && regionless(s, host);
        });
        listen(s, v);
        if (v.isAttachedToWindow() || regionless(s, v)) sync(s);
        logOnce(s.surface, "LIVE_GLASS_APPLY", "surface=" + s.surface + " host=" + v.getClass().getName()
                + " native=" + (bg == null ? "none" : NativeShape.unwrap(bg).getClass().getSimpleName()) + " sem=" + (sem != null)
                + " radius=" + radii[0] + " attached=" + v.isAttachedToWindow());
        return Outcome.APPLIED;
    }
    private Outcome nativeOutcome(State s, String reason) {
        logOnce(s.surface, "LIVE_GLASS_NATIVE", "surface=" + s.surface + " reason=" + reason);
        render(s, "NATIVE", reason);
        return Outcome.NATIVE;
    }

    private Outcome reassert(State s) {
        View v = s.host.get();
        if (v == null) return Outcome.DENIED;
        Drawable bg = v.getBackground();
        if (bg != s.glass && !blurDrawable(bg)) s.displacedBackground = null;
        Object held = nativeBlur.held(v);
        if (held != null) {
            // Samsung re-installed its own blur (setBlurBG, a.N). It leaves only while the glass is
            // really drawn; otherwise the host goes back to it at once.
            s.semInfo = held;
            if (!canShow(s, v) || s.glass.blur() == null) { nativeBlurBack(s, "rewritten"); return Outcome.NATIVE; }
            nativeBlur.clear(v);
            bg = v.getBackground();
            if (bg == s.glass) return Outcome.REASSERTED;
            if (bg == null || blurDrawable(bg)) bg = s.glass.nativeDrawable();
        } else if (bg == s.glass) {
            // The background is ours, but Samsung can recolour or resize the source CardView
            // without replacing the child-layer wrapper. Refresh before companions() makes the
            // source CardView transparent again; these setters are no-ops if values are unchanged.
            refreshShape(s, v, s.glass.nativeDrawable());
            // The layer behind may also have changed (setBlurBG on an ancestor).
            if (regionless(s, v) != s.glass.regionless()) sync(s);
            return Outcome.INTACT;
        } else if (blurDrawable(bg)) {
            s.displacedBackground = bg;
            bg = s.glass.nativeDrawable();
        } else if (bg == null && !s.allowEmptyNative) {
            // Samsung removed the background on purpose: the glass would add a pane it does not want.
            LiveGlassDrawable g = s.glass;
            s.glass = null;
            g.release();
            removeListeners(s);
            if (s.lastShown && s.onShown != null) { s.lastShown = false; s.onShown.accept(v, false); }
            if (s.semInfo != null) { if (nativeBlur.held(v) == null) nativeBlur.set(v, s.semInfo); s.semInfo = null; }
            return Outcome.NATIVE;
        } else if (bg instanceof LiveGlassDrawable) {
            bg = ((LiveGlassDrawable) bg).nativeDrawable();
        }
        if (bg != null && (NativeShape.interactive(bg) || NativeShape.cardLike(v))) { forget(v, "nativeChanged"); return Outcome.RESTORED; }
        refreshShape(s, v, bg);
        writing = true;
        try {
            v.setBackground(s.glass);
            s.glass.setNative(bg);
        } finally { writing = false; }
        if (bg != null) s.lastNative = bg;
        if (regionless(s, v) != s.glass.regionless()) sync(s);
        return Outcome.REASSERTED;
    }

    /** Samsung may mutate a shape source in place while the drawable wrapper stays installed. */
    private void refreshShape(State s, View host, Drawable nativeBackground) {
        View source = s.shapeSource.get();
        Drawable shape = source != null ? source.getBackground() : nativeBackground;
        View shapeHost = source != null ? source : host;
        float[] radii = NativeShape.radii(shape, shapeHost);
        if (radii != null) s.glass.setRadii(radii);
        s.glass.setNativeColor(NativeShape.color(shape, shapeHost));
    }

    private void restore(State s, String reason) {
        View v = s.host.get();
        LiveGlassDrawable g = s.glass;
        s.glass = null;
        removeListeners(s);
        if (g == null) return;
        Drawable nativeBg = s.displacedBackground != null ? s.displacedBackground : g.nativeDrawable();
        s.displacedBackground = null;
        g.release();
        if (v == null) return;
        if (s.lastShown && s.onShown != null) { s.lastShown = false; s.onShown.accept(v, false); }
        writing = true;
        try {
            // Only our wrapper is replaced: a background Samsung wrote meanwhile stays.
            if (v.getBackground() == g) v.setBackground(nativeBg);
        } finally { writing = false; }
        if (s.semInfo != null) { if (nativeBlur.held(v) == null) nativeBlur.set(v, s.semInfo); s.semInfo = null; }
        render(s, "RESTORED", reason);
        Probe.log("LIVE_GLASS_RESTORE", "surface=" + s.surface + " reason=" + reason + " host=" + v.getClass().getSimpleName());
    }

    private void listen(State s, View v) {
        if (s.attach != null) return;
        s.attach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { s.backgroundRepairs = 0; sync(s); }
            @Override public void onViewDetachedFromWindow(View view) {
                if (s.glass != null) {
                    s.glass.setRegionless(false);
                    s.glass.setBlur(null);
                    s.glass.setGlass(false);
                    shown(s, view, false);
                }
                render(s, "DETACHED", "windowLost");
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
        sync(s, false);
    }
    private void sync(State s, boolean frame) {
        View v = s.host.get();
        if (v == null) return;
        boolean want = frame ? s.available && !s.regionBroken && v.isAttachedToWindow() : canShow(s, v);
        s.available = want;
        if (s.glass == null) {
            // A host left on Samsung's blur is wrapped as soon as the region can exist.
            if (!s.waiting || !want) { if (s.waiting) render(s, "WAITING_SAMSUNG", "sync"); return; }
            Object owner = s.owner.get();
            if (owner == null || s.recheck == null || !s.recheck.test(owner)) return;
            apply(s);
            return;
        }
        LiveGlassDrawable g = s.glass;
        boolean before = g.showsGlass();
        // Both modes need this listener: Samsung changes radius/alpha/bounds during animations
        // without another adapter writer. Never leave a regionless host outside pre-draw sync.
        if (v.isAttachedToWindow() && s.preDraw == null) {
            ViewTreeObserver o = v.getViewTreeObserver();
            s.preDraw = () -> {
                // Only classified hosts are inspected. Samsung may replace a toast background
                // during addView/attach, after the factory hook has already returned.
                View host = s.host.get();
                if (host == null || s.glass == null) return true;
                if (host.getBackground() != s.glass) {
                    if (++s.backgroundRepairs > 3) {
                        forget(host, "backgroundOwnershipLost");
                        return true;
                    }
                    reassert(s);
                    if (s.glass == null) return true;
                }
                sync(s, true);
                return true;
            };
            s.observer = o;
            o.addOnPreDrawListener(s.preDraw);
        }
        if (want && regionless(s, v)) {
            g.setRegionless(true);
            g.setGlass(true);
            shown(s, v, g.showsGlass());
            if (s.onSync != null) s.onSync.accept(v, g.showsGlass());
            fade(s);
            render(s, "REGIONLESS", "samsungAncestor");
            if (before != g.showsGlass()) v.invalidate();
            return;
        }
        g.setRegionless(false);
        if (want && g.blur() == null) {
            try { g.setBlur(neutral(blur.create(v, s.surface))); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                logOnce(s.surface, "LIVE_GLASS_NO_REGION", "surface=" + s.surface + " error=" + e.getClass().getSimpleName());
                g.setBlur(null);
                s.regionBroken = true;
            }
        } else if (!want && g.blur() != null) g.setBlur(null);
        // Samsung's blur is never removed for a pane that is not drawn: back to it now.
        if (s.semInfo != null && (!want || g.blur() == null)) { nativeBlurBack(s, want ? "noRegion" : "unavailable"); return; }
        if (want && v.getBackground() != g) {
            reassert(s);
            if (s.glass != g) return;
        }
        g.setGlass(want);
        shown(s, v, g.showsGlass());
        if (s.onSync != null) s.onSync.accept(v, g.showsGlass());
        fade(s);
        render(s, g.showsGlass() ? "OWN_REGION" : "NATIVE", want ? "sync" : "unavailable");
        if (before != g.showsGlass()) v.invalidate();
    }
    private boolean regionless(State s, View v) {
        try { return s.nativeRegion != null && s.nativeRegion.test(v); }
        catch (RuntimeException e) { return false; }
    }
    /** Every live region carries the neutral colour curve of the approved glass. */
    private static BackgroundBlurBridge neutral(BackgroundBlurBridge b) {
        if (b != null) b.setNeutralCurve(true);
        return b;
    }
    private boolean canShow(State s, View v) {
        return !s.regionBroken && gate.allows(s.surface) && v.isAttachedToWindow()
                && blur.enabled(v) && policy.glassAllowed(s.surface, v);
    }
    /**
     * The glass is not drawn on a host that had Samsung's blur: the native background and that
     * blur go back exactly, the host stays tracked and is wrapped again when the region can exist.
     */
    private void nativeBlurBack(State s, String reason) {
        View v = s.host.get();
        LiveGlassDrawable g = s.glass;
        if (v == null || g == null) return;
        s.glass = null;
        s.waiting = true;
        removePreDraw(s);
        Drawable nativeBg = s.displacedBackground != null ? s.displacedBackground : g.nativeDrawable();
        s.displacedBackground = null;
        if (g.nativeDrawable() != null) s.lastNative = g.nativeDrawable();
        g.release();
        shown(s, v, false);
        writing = true;
        try { if (v.getBackground() == g) v.setBackground(nativeBg); }
        finally { writing = false; }
        if (s.semInfo != null && nativeBlur.held(v) == null) nativeBlur.set(v, s.semInfo);
        render(s, "WAITING_SAMSUNG", reason);
        logOnce(s.surface, "LIVE_GLASS_SAMSUNG_BLUR", "surface=" + s.surface + " reason=" + reason);
    }
    /** Mid-draw failure of the region: native at once, companions (CardView, gradient) restored. */
    private void regionFailed(State s) {
        s.regionBroken = true;
        View v = s.host.get();
        if (v == null || s.glass == null) return;
        s.glass.setGlass(false);
        shown(s, v, false);
        render(s, "DRAW_FAILURE", "regionBroken");
        logOnce(s.surface, "LIVE_GLASS_NO_REGION", "surface=" + s.surface + " stage=draw");
        // Not from inside the draw: the background is replaced right after it.
        LiveGlassDrawable g = s.glass;
        if (s.semInfo != null) v.post(() -> { if (s.glass == g) nativeBlurBack(s, "drawFailure"); });
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
    /** Logs only transitions; the expensive hierarchy snapshot is never taken on steady frames. */
    private void render(State s, String mode, String reason) {
        if (!s.render.changed(mode, reason)) return;
        View v = s.host.get();
        LiveGlassDrawable g = s.glass;
        Probe.log("LIVE_RENDER_STATE", "surface=" + s.surface + " mode=" + mode + " reason=" + reason
                + " ownRegion=" + (g != null && g.blur() != null)
                + " regionless=" + (g != null && g.regionless())
                + " shown=" + (g != null && g.showsGlass()) + " semSaved=" + (s.semInfo != null)
                + " faded=" + (v == null ? "?" : accumulatedAlpha(v))
                + " " + LiveRenderProbe.view(v) + " ancestors=" + LiveRenderProbe.ancestors(v));
    }
    /**
     * Logs a state when it differs from the last one of the same surface and event: repeated
     * callbacks in the same state (dozens per second on the Now Bar) write nothing, while every new
     * state stays visible for the whole process (no fixed budget hiding later events).
     */
    private final Map<String, String> lastLogged = new java.util.HashMap<>();
    private void logOnce(LiveSurface surface, String event, String detail) {
        String key = surface + "/" + event;
        String state = detail.replaceAll(" radius=[^ ]*| attached=[^ ]*", "");
        if (state.equals(lastLogged.get(key))) return;
        lastLogged.put(key, state);
        Probe.log(event, detail);
    }
}


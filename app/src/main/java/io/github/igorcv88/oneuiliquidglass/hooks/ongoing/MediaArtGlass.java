package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/**
 * Album art over the module's glass in the shade's media player and in the Live Updates stack's
 * media card (both QSMediaCornerRoundedView#media_carousel_layout, same subtree on DZIF).
 *
 * <p>Two layers occlude the glass (PR #159, DZIF, 04/11/2026, both surfaces): shade_album_art
 * (originImage, circleReveal/newImage, and its own opaque ColorDrawable) and its sibling above,
 * sec_album_art, which leaves a dark film over the glass when only the art is hidden. Hiding only
 * sec_album_art changed nothing visible; hiding both showed the glass with working controls. #158
 * lowered only shade_album_art, so the film stayed and nothing changed on screen.</p>
 *
 * <p>Applied while glass is behind, in one pre-draw (no frame with only one of them changed):
 * shade_album_art gets the configured opacity as a group alpha (originImage and newImage share it
 * and the circle reveal composes inside one layer, so their overlap is not denser during a track
 * change), its ColorDrawable alpha 0, and sec_album_art alpha 0. Controls, header, text and
 * WaveSeekbar are not touched. A value Samsung (or a framework Fade) writes meanwhile becomes the
 * native value and ours follows it proportionally (native x opacity, native x 0). Without glass,
 * with the property off, on detach or error, every native value is handed back exactly.</p>
 *
 * <p>Opacity: the app's "Opacidade da capa de mídia" slider (5%-100% in 5% steps), the same
 * key as {@code debug.oulg.mediaart}, read through Tuning (app setting first, then the system
 * property): unset = 0.50; a number is clamped to [0.05, 1.00]; "0" or "off" keeps the media
 * native. {@code debug.oulg.mediaart.alpha}: "transition" (default,
 * View.setTransitionAlpha, which leaves Samsung's own alpha field alone) or "view"
 * (View.setAlpha, the call #159 showed effective). Both are read when a root attaches and when it
 * draws again after a pause of {@link #SESSION_GAP_MS}, never on every frame. Per frame: a few
 * reference and float compares on cached views; no lookup, reflection, allocation or log.</p>
 */
public final class MediaArtGlass implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    public static final String PROPERTY = "debug.oulg.mediaart";
    public static final String PROBE_PROPERTY = "debug.oulg.mediaart.probe";
    public static final String ALPHA_PROPERTY = "debug.oulg.mediaart.alpha";
    static final float DEFAULT_OPACITY = io.github.igorcv88.oneuiliquidglass.glass.Tuning.MEDIA_ART_DEFAULT,
            MIN_OPACITY = io.github.igorcv88.oneuiliquidglass.glass.Tuning.MEDIA_ART_MIN, MAX_OPACITY = 1.00f,
            OFF = io.github.igorcv88.oneuiliquidglass.glass.Tuning.MEDIA_ART_NATIVE;
    static final long SESSION_GAP_MS = 1000;
    private static final String ROOT_CLASS = "com.android.systemui.media.QSMediaCornerRoundedView";

    /** Whether the module's glass is drawn under a shade notification row right now. */
    public interface RowGlass { boolean drawsGlass(View row); }
    interface PropertySource { String get(); }

    /** Resource entry names; tests swap them for framework ids that Robolectric knows. */
    static final class Names {
        final String root, art, stackHolder, overlay;
        Names(String root, String art, String stackHolder) { this(root, art, stackHolder, "sec_album_art"); }
        Names(String root, String art, String stackHolder, String overlay) {
            this.root = root; this.art = art; this.stackHolder = stackHolder; this.overlay = overlay;
        }
    }
    static final Names DZIF = new Names("media_carousel_layout", "shade_album_art", "stack_expand_contents");

    static Names names = DZIF;
    static RowGlass rowGlass = row -> false;
    /** The opacity, already parsed (production: Tuning, which reads the app setting first). */
    interface OpacitySource { float get(); }
    static OpacitySource opacitySource = () -> io.github.igorcv88.oneuiliquidglass.glass.Tuning.get().mediaArt;
    static PropertySource probeProperty = () -> systemProperty(PROBE_PROPERTY);
    static PropertySource alphaProperty = () -> systemProperty(ALPHA_PROPERTY);
    static LongSupplier clock = SystemClock::uptimeMillis;
    private static final Map<View, MediaArtGlass> WATCHED = new WeakHashMap<>();
    private static Method getter;

    enum Context { NONE, SHADE, STACK }

    private final java.lang.ref.WeakReference<View> root;
    private ViewTreeObserver observer;
    private Context context = Context.NONE;
    private java.lang.ref.WeakReference<View> anchor;
    private View art;
    private View overlayView;
    private float opacity = DEFAULT_OPACITY;
    /** true: View.setAlpha; false: View.setTransitionAlpha (debug.oulg.mediaart.alpha). */
    private boolean viewAlpha;
    private int probeMode;
    private int appliedProbeMode;
    private View leasedProbeArt, leasedProbeOverlay;
    private float nativeProbeArtAlpha, nativeProbeOverlayAlpha;
    private long probeStartMs;
    private long lastFrame = Long.MIN_VALUE / 2;
    private boolean rootChecked, isRoot;
    // Leases: what was changed, on which object, and the native value to hand back.
    private final AlphaLease artLease = new AlphaLease(), overlayLease = new AlphaLease();
    private ColorDrawable leasedFill;
    private int nativeFillAlpha;
    private int reasserts;
    /** The reason of the last native line; null while applied (and before any line). */
    private String lastReason;

    private MediaArtGlass(View root) { this.root = new java.lang.ref.WeakReference<>(root); }

    /** From the hook installation: follows every QSMediaCornerRoundedView SystemUI creates. */
    public static void install(ClassLoader loader, RowGlass rows) {
        if (rows != null) rowGlass = rows;
        Class<?> c;
        try { c = XposedHelpers.findClassIfExists(ROOT_CLASS, loader); }
        catch (RuntimeException | LinkageError e) { c = null; }
        if (c == null) { Probe.log("MEDIA_ART", "install=false reason=noClass"); return; }
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { if (p.thisObject instanceof View) watch((View) p.thisObject); }
                catch (RuntimeException | LinkageError e) { Probe.error("MEDIA_ART_WATCH_FAILED", e); }
            }
        });
        Probe.log("MEDIA_ART", "install=true");
    }

    /** Idempotent. The resource id is only known after inflation, so the root test waits for attach. */
    static MediaArtGlass watch(View v) {
        MediaArtGlass m = WATCHED.get(v);
        if (m != null) return m;
        m = new MediaArtGlass(v);
        WATCHED.put(v, m);
        v.addOnAttachStateChangeListener(m);
        if (v.isAttachedToWindow()) m.onViewAttachedToWindow(v);
        return m;
    }

    @Override public void onViewAttachedToWindow(View v) {
        if (!rootChecked) {
            rootChecked = true;
            isRoot = names.root.equals(LiveStackExternalProbe.resourceName(v));
            if (!isRoot) {
                // sec_qs_media_controls shares the class: nothing to follow there.
                v.removeOnAttachStateChangeListener(this);
                WATCHED.remove(v);
                return;
            }
        }
        // The same carousel may be re-hosted: the context is resolved again on every attach.
        resolveContext(v);
        art = NativeIds.find(v, names.art);
        overlayView = findOverlaySibling(art);
        readProperties();
        lastFrame = clock.getAsLong();
        observer = v.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override public void onViewDetachedFromWindow(View v) {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        restore("detached", null);
        restoreProbe("detached");
        overlayView = null;
        // WATCHED holds this strongly: a child of the root kept here would keep its weak key
        // (the root, through the parent chain) and the whole card alive. Attach finds it again.
        art = null;
    }

    @Override public boolean onPreDraw() {
        try { sync(); }
        catch (RuntimeException | LinkageError e) { restore("error", null); Probe.error("MEDIA_ART_FAILED", e); }
        return true;
    }

    void sync() {
        View r = root.get();
        if (r == null) return;
        long now = clock.getAsLong();
        if (now - lastFrame > SESSION_GAP_MS) {
            readProperties();
            if (art == null || !art.isAttachedToWindow()) {
                art = NativeIds.find(r, names.art);
                overlayView = findOverlaySibling(art);
            }
        }
        lastFrame = now;
        if (probeMode != 0) {
            // Diagnostic-only: isolate the real pixel producer even if glass eligibility flickers.
            restore("probe", null);
            applyProbe(probeMode);
            return;
        }
        restoreProbe("disabled");
        if (opacity == OFF) { restore("off", null); return; }
        if (context == Context.NONE) { restore("noContext", null); return; }
        if (art == null) { restore("noArt", null); return; }
        if (!glassBehind()) { restore("noGlass", null); return; }
        Drawable fill = art.getBackground();
        if (fill != null && !(fill instanceof ColorDrawable)) {
            // An unknown opaque layer: a lower alpha would only darken the art over it.
            restore("unknownFill", fill);
            return;
        }
        apply(fill == null ? null : (ColorDrawable) fill);
    }

    private boolean glassBehind() {
        View a = anchor == null ? null : anchor.get();
        if (a == null) return false;
        if (context == Context.STACK) return a.getBackground() instanceof LiveGlassDrawable;
        return rowGlass.drawsGlass(a);
    }

    private void apply(ColorDrawable fill) {
        boolean entering = !artLease.held() && leasedFill == null;
        // Both layers change in this same pre-draw: no frame shows the art translucent under the
        // film, or the film gone over the opaque art.
        boolean changed = artLease.hold(art, viewAlpha, opacity);
        if (overlayView != null) changed |= overlayLease.hold(overlayView, viewAlpha, 0f);
        else overlayLease.release();
        if (fill != leasedFill) {
            restoreFill();
            if (fill != null) {
                // mutate(): a ColorDrawable inflated from a resource shares its state.
                leasedFill = (ColorDrawable) fill.mutate();
                nativeFillAlpha = leasedFill.getAlpha();
            }
            changed = true;
        }
        if (leasedFill != null && leasedFill.getAlpha() != 0) {
            // Samsung's setColor() brings the alpha back: that value is the native one now.
            if (!entering) { nativeFillAlpha = leasedFill.getAlpha(); reasserts++; }
            leasedFill.setAlpha(0);
        }
        if (!changed) return;
        lastReason = null;
        Probe.log("MEDIA_ART", "state=applied context=" + context.name().toLowerCase(Locale.ROOT)
                + " method=" + (viewAlpha ? "view" : "transition") + " opacity=" + fmt(opacity)
                + " art=" + id(art) + " artNative=" + fmt(artLease.nativeValue) + " artApplied=" + fmt(artLease.applied)
                + " overlay=" + id(overlayView)
                + (overlayView == null ? "" : " overlayNative=" + fmt(overlayLease.nativeValue) + " overlayApplied=" + fmt(overlayLease.applied))
                + " fill=" + (leasedFill == null ? "none" : "ColorDrawable/alpha" + nativeFillAlpha)
                + " artShown=" + art.isShown() + " root=" + id(root.get()));
    }

    /** reason is a literal: an unchanged reason is recognised by identity, without building a string. */
    private void restore(String reason, Drawable fill) {
        boolean held = artLease.held() || overlayLease.held() || leasedFill != null;
        int nativeWrites = artLease.nativeWrites + overlayLease.nativeWrites;
        artLease.release();
        overlayLease.release();
        restoreFill();
        if (!held && reason == lastReason) return;
        lastReason = reason;
        Probe.log("MEDIA_ART", "state=native context=" + context.name().toLowerCase(Locale.ROOT) + " reason=" + reason
                + (fill == null ? "" : " fill=" + fill.getClass().getName())
                + " restored=" + held + " artAlpha=" + (art == null ? "null" : fmt(art.getAlpha()) + "/" + fmt(art.getTransitionAlpha()))
                + " overlayAlpha=" + (overlayView == null ? "null" : fmt(overlayView.getAlpha()) + "/" + fmt(overlayView.getTransitionAlpha()))
                + " nativeWrites=" + nativeWrites + " fillReasserts=" + reasserts + " root=" + id(root.get()));
        reasserts = 0;
    }

    /**
     * One view's alpha (View alpha or transition alpha) held at native x factor. A value written
     * by someone else while held is taken as the new native value; release hands back the native
     * value only if ours is still in place.
     */
    static final class AlphaLease {
        private View view;
        private boolean viewAlpha;
        float nativeValue = Float.NaN, applied = Float.NaN;
        int nativeWrites;

        boolean held() { return view != null; }

        /** True when the target, the method or the factor changed (a line worth logging). */
        boolean hold(View v, boolean viewMode, float factor) {
            boolean fresh = v != view || viewMode != viewAlpha;
            if (fresh) {
                release();
                view = v;
                viewAlpha = viewMode;
                nativeValue = read();
                applied = Float.NaN;
            }
            float current = read();
            boolean nativeWrite = !fresh && current != applied;
            if (nativeWrite) { nativeValue = current; nativeWrites++; }
            float want = nativeValue * factor;
            boolean factorChanged = !fresh && !nativeWrite && want != applied;
            if (current != want) write(want);
            applied = want;
            return fresh || factorChanged;
        }

        void release() {
            if (view != null && read() == applied) write(nativeValue);
            view = null;
            applied = Float.NaN;
            nativeWrites = 0;
        }

        private float read() { return viewAlpha ? view.getAlpha() : view.getTransitionAlpha(); }
        private void write(float f) { if (viewAlpha) view.setAlpha(f); else view.setTransitionAlpha(f); }
    }

    private void restoreFill() {
        // Only our own zero is undone: a value Samsung wrote meanwhile is already native.
        if (leasedFill != null && leasedFill.getAlpha() == 0) leasedFill.setAlpha(nativeFillAlpha);
        leasedFill = null;
    }

    /**
     * Opt-in visual isolation. Modes: 1=shade_album_art, 2=sec_album_art, 3=both.
     * Only cached view references and float comparisons run per frame; logs occur on transitions.
     * These tests intentionally ignore the glass gate to distinguish compositing from eligibility.
     */
    private void applyProbe(int mode) {
        boolean hideArt = (mode & 1) != 0;
        boolean hideOverlay = (mode & 2) != 0;
        boolean entering = appliedProbeMode != mode
                || (hideArt && leasedProbeArt != art)
                || (hideOverlay && leasedProbeOverlay != overlayView);
        if (entering) {
            restoreProbe("changed");
            appliedProbeMode = mode;
            probeStartMs = clock.getAsLong();
            if (hideArt && art != null) {
                leasedProbeArt = art;
                nativeProbeArtAlpha = art.getAlpha();
            }
            if (hideOverlay && overlayView != null) {
                leasedProbeOverlay = overlayView;
                nativeProbeOverlayAlpha = overlayView.getAlpha();
            }
        }
        // Reassert only if Samsung overwrote our diagnostic alpha; no reflection or allocations.
        if (leasedProbeArt != null && leasedProbeArt.getAlpha() != 0f) leasedProbeArt.setAlpha(0f);
        if (leasedProbeOverlay != null && leasedProbeOverlay.getAlpha() != 0f) leasedProbeOverlay.setAlpha(0f);
        if (entering) {
            Rect visible = new Rect();
            boolean onScreen = art != null && art.getGlobalVisibleRect(visible);
            Probe.log("MEDIA_ART_PROBE", "state=applied mode=" + mode
                    + " context=" + context.name().toLowerCase(Locale.ROOT)
                    + " art=" + id(art) + " overlay=" + id(overlayView)
                    + " artShown=" + (art != null && art.isShown())
                    + " onScreen=" + onScreen + " rect=" + visible.flattenToString()
                    + " glassBehind=" + glassBehind()
                    + " artNativeAlpha=" + (art == null ? "null" : fmt(hideArt ? nativeProbeArtAlpha : art.getAlpha()))
                    + " overlayNativeAlpha=" + (overlayView == null ? "null" : fmt(hideOverlay ? nativeProbeOverlayAlpha : overlayView.getAlpha()))
                    + " artAppliedAlpha=" + (art == null ? "null" : fmt(art.getAlpha()))
                    + " overlayAppliedAlpha=" + (overlayView == null ? "null" : fmt(overlayView.getAlpha())));
        }
    }

    private void restoreProbe(String reason) {
        if (appliedProbeMode == 0) return;
        if (leasedProbeArt != null && leasedProbeArt.getAlpha() == 0f) {
            leasedProbeArt.setAlpha(nativeProbeArtAlpha);
        }
        if (leasedProbeOverlay != null && leasedProbeOverlay.getAlpha() == 0f) {
            leasedProbeOverlay.setAlpha(nativeProbeOverlayAlpha);
        }
        Probe.log("MEDIA_ART_PROBE", "state=restored mode=" + appliedProbeMode
                + " context=" + context.name().toLowerCase(Locale.ROOT)
                + " reason=" + reason + " durationMs=" + (clock.getAsLong() - probeStartMs));
        leasedProbeArt = null;
        leasedProbeOverlay = null;
        appliedProbeMode = 0;
    }

    private static View findOverlaySibling(View art) {
        if (art == null || !(art.getParent() instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) art.getParent();
        int index = g.indexOfChild(art);
        for (int i = index + 1; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (names.overlay.equals(LiveStackExternalProbe.resourceName(child))) return child;
        }
        return null;
    }

    static int parseProbe(String raw) {
        if (raw == null) return 0;
        String mode = raw.trim();
        return ("1".equals(mode) || "2".equals(mode) || "3".equals(mode))
                ? Integer.parseInt(mode) : 0;
    }

    private static String readProbeProperty() {
        try { return probeProperty.get(); } catch (RuntimeException e) { return null; }
    }

    private void resolveContext(View v) {
        context = Context.NONE;
        anchor = null;
        ViewParent p = v.getParent();
        for (int i = 0; i < 12 && p instanceof View; i++, p = p.getParent()) {
            View a = (View) p;
            if (i == 0 && names.stackHolder.equals(LiveStackExternalProbe.resourceName(a))) {
                context = Context.STACK; anchor = new java.lang.ref.WeakReference<>(a); return;
            }
            if (a.getClass().getName().endsWith("ExpandableNotificationRow")) {
                context = Context.SHADE; anchor = new java.lang.ref.WeakReference<>(a); return;
            }
        }
    }

    private static String id(View v) { return v == null ? "null" : Integer.toHexString(System.identityHashCode(v)); }

    static float parse(String raw) { return io.github.igorcv88.oneuiliquidglass.glass.Tuning.mediaArtOpacity(raw); }

    private static float readOpacity() {
        try { return opacitySource.get(); } catch (RuntimeException | LinkageError e) { return DEFAULT_OPACITY; }
    }

    private void readProperties() {
        opacity = readOpacity();
        probeMode = parseProbe(readProbeProperty());
        viewAlpha = parseAlphaMethod(read(alphaProperty));
    }

    /** "view" selects View.setAlpha; anything else keeps setTransitionAlpha. */
    static boolean parseAlphaMethod(String raw) {
        return raw != null && "view".equals(raw.trim().toLowerCase(Locale.ROOT));
    }

    private static String read(PropertySource source) {
        try { return source.get(); } catch (RuntimeException e) { return null; }
    }

    private static String systemProperty(String name) {
        try {
            if (getter == null) getter = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return (String) getter.invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String fmt(float f) { return Float.isNaN(f) ? "none" : String.format(Locale.ROOT, "%.2f", f); }

    // Tests.
    Context contextForTest() { return context; }
    View artForTest() { return art; }
    static void resetForTest() {
        WATCHED.clear();
        names = DZIF;
        rowGlass = row -> false;
        opacitySource = () -> io.github.igorcv88.oneuiliquidglass.glass.Tuning.get().mediaArt;
        probeProperty = () -> systemProperty(PROBE_PROPERTY);
        alphaProperty = () -> systemProperty(ALPHA_PROPERTY);
        clock = SystemClock::uptimeMillis;
    }
}

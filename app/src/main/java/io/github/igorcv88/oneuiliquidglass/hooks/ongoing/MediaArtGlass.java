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
 * <p>Survey facts (OULG_MEDIA_SURVEY_20261104_173145): below originImage and newImage the only
 * opaque layer is shade_album_art's own ColorDrawable (alpha 255, opaque); media_carousel_layout,
 * sec_qs_media_controls and circleReveal carry ColorDrawables at alpha 0. Behind the subtree sits
 * our material: the row's NotificationBackgroundView in the shade, the LiveGlassDrawable of
 * stack_expand_contents in the stack. Lowering the art's opacity without clearing that
 * ColorDrawable would only darken the art over Samsung's fill.</p>
 *
 * <p>Applied while glass is behind: shade_album_art gets transitionAlpha = opacity (Samsung's own
 * alpha is untouched; final alpha = alpha x transitionAlpha) and its ColorDrawable alpha 0. The
 * opacity is a group alpha on the parent of originImage and newImage: both get the same value and
 * the circle reveal composes inside one layer, so the overlap of the two images is not denser
 * than the rest during a track change. Controls, header, WaveSeekbar and sec_album_art (the
 * legibility gradients above the art) are not touched. Without glass, the property off, or on
 * detach, both values are handed back exactly.</p>
 *
 * <p>{@code debug.oulg.mediaart}: unset = 0.80; a number is clamped to [0.50, 1.00]; "0" or "off"
 * keeps the media native. Read when a root attaches and when it draws again after a pause of
 * {@link #SESSION_GAP_MS} (a new shade opening or stack showing), never on every frame. The
 * per-frame work is a few reference and float compares; no lookup, reflection or allocation.</p>
 */
public final class MediaArtGlass implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    public static final String PROPERTY = "debug.oulg.mediaart";
    public static final String PROBE_PROPERTY = "debug.oulg.mediaart.probe";
    static final float DEFAULT_OPACITY = 0.80f, MIN_OPACITY = 0.50f, MAX_OPACITY = 1.00f, OFF = -1f;
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
    static PropertySource property = MediaArtGlass::systemProperty;
    static PropertySource probeProperty = () -> systemProperty(PROBE_PROPERTY);
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
    private int probeMode;
    private int appliedProbeMode;
    private View leasedProbeArt, leasedProbeOverlay;
    private float nativeProbeArtAlpha, nativeProbeOverlayAlpha;
    private long probeStartMs;
    private long lastFrame = Long.MIN_VALUE / 2;
    private boolean rootChecked, isRoot;
    // Leases: what was changed, on which object, and the native value to hand back.
    private View alphaHost;
    private float appliedOpacity, nativeTransitionAlpha;
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
        opacity = parse(readProperty());
        probeMode = parseProbe(readProbeProperty());
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
            opacity = parse(readProperty());
            probeMode = parseProbe(readProbeProperty());
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
        boolean entering = alphaHost == null && leasedFill == null;
        boolean changed = entering;
        if (alphaHost != art) {
            restoreAlpha();
            alphaHost = art;
            nativeTransitionAlpha = art.getTransitionAlpha();
            changed = true;
        }
        if (art.getTransitionAlpha() != opacity) {
            // Our own value overwritten (a framework Fade, for instance): counted, then reasserted.
            if (appliedOpacity == opacity) reasserts++;
            else changed = true;
            art.setTransitionAlpha(opacity);
            appliedOpacity = opacity;
        }
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
        View r = root.get();
        Probe.log("MEDIA_ART", "state=applied context=" + context.name().toLowerCase(Locale.ROOT)
                + " opacity=" + fmt(opacity) + " nativeTAlpha=" + fmt(nativeTransitionAlpha)
                + " fill=" + (leasedFill == null ? "none" : "ColorDrawable/alpha" + nativeFillAlpha)
                + (entering ? overlay() : "") + " root=" + id(r));
    }

    /** reason is a literal: an unchanged reason is recognised by identity, without building a string. */
    private void restore(String reason, Drawable fill) {
        boolean held = alphaHost != null || leasedFill != null;
        restoreAlpha();
        restoreFill();
        if (!held && reason == lastReason) return;
        lastReason = reason;
        Probe.log("MEDIA_ART", "state=native context=" + context.name().toLowerCase(Locale.ROOT) + " reason=" + reason
                + (fill == null ? "" : " fill=" + fill.getClass().getName()) + " reasserts=" + reasserts + " root=" + id(root.get()));
        reasserts = 0;
    }

    private void restoreAlpha() {
        if (alphaHost != null && alphaHost.getTransitionAlpha() == appliedOpacity) alphaHost.setTransitionAlpha(nativeTransitionAlpha);
        alphaHost = null;
        appliedOpacity = Float.NaN;
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

    /**
     * sec_album_art (above the art) reports an opaque white GradientDrawable background while the
     * art shows through it on device, so something not captured by the survey composes it. Its
     * public compositing state is logged when the art starts showing the glass, to explain what
     * the glass shows through.
     */
    private String overlay() {
        View parent = art.getParent() instanceof android.view.ViewGroup ? (View) art.getParent() : null;
        if (parent == null) return "";
        android.view.ViewGroup g = (android.view.ViewGroup) parent;
        int index = g.indexOfChild(art);
        View above = index >= 0 && index + 1 < g.getChildCount() ? g.getChildAt(index + 1) : null;
        if (above == null) return " overlay=none";
        return " overlay=" + LiveStackExternalProbe.resourceName(above) + "/layer" + above.getLayerType()
                + "/bgBlend" + above.getBackgroundTintBlendMode() + "/fgBlend" + above.getForegroundTintBlendMode()
                + "/overlap" + above.hasOverlappingRendering();
    }

    private static String id(View v) { return v == null ? "null" : Integer.toHexString(System.identityHashCode(v)); }

    static float parse(String raw) {
        if (raw == null) return DEFAULT_OPACITY;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return DEFAULT_OPACITY;
        if (s.equals("0") || s.equals("off") || s.equals("false")) return OFF;
        try {
            float f = Float.parseFloat(s);
            if (Float.isNaN(f)) return DEFAULT_OPACITY;
            return Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, f));
        } catch (NumberFormatException e) {
            return DEFAULT_OPACITY;
        }
    }

    private static String readProperty() {
        try { return property.get(); } catch (RuntimeException e) { return null; }
    }

    private static String systemProperty() { return systemProperty(PROPERTY); }

    private static String systemProperty(String name) {
        try {
            if (getter == null) getter = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            return (String) getter.invoke(null, name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String fmt(float f) { return String.format(Locale.ROOT, "%.2f", f); }

    // Tests.
    Context contextForTest() { return context; }
    View artForTest() { return art; }
    static void resetForTest() {
        WATCHED.clear();
        names = DZIF;
        rowGlass = row -> false;
        property = MediaArtGlass::systemProperty;
        probeProperty = () -> systemProperty(PROBE_PROPERTY);
        clock = SystemClock::uptimeMillis;
    }
}

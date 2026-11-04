package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * E2 / option A: experimental, reversible handling of DZIF's bottom stack indicator.
 *
 * <p>E1 (docs/live-stack-external-e1.md) found {@code ongoing_card_bottom_shadow_frame}, a sibling
 * of CardStackView inside {@code ongoing_card_stack_and_shadow_layout}, whose children
 * {@code ongoing_card_bottom_shadow1_view} (scale 0.92, alpha 0.5, translationY -195) and
 * {@code ongoing_card_bottom_shadow2_view} (scale 0.84, alpha 0.3, translationY -171) draw a
 * RippleDrawable with a {@code #FA3A3A3D} content layer. The frame sits at the stack's bottom and
 * does not clip its children, so they paint upwards behind the front card. Natively that card is
 * opaque; behind the translucent glass the whole shape shows through. That attribution is still a
 * hypothesis: this class exists to test it on the device.</p>
 *
 * <p>Selected with {@code setprop debug.oulg.stackshadow N}; default 0 keeps today's behaviour:</p>
 * <ul>
 *   <li>0 native, nothing touched (everything this class changed is restored);</li>
 *   <li>1 attribution: paint the content layer of both indicators magenta;</li>
 *   <li>2 suppress the content layer of shadow1 only;</li>
 *   <li>3 suppress the content layer of shadow2 only;</li>
 *   <li>4 suppress both (option B preview);</li>
 *   <li>5 option A2: clip the frame to its own bounds, so only what lies below the card remains;</li>
 *   <li>6 option A: clip each indicator to the part below the frame's top, from its transform;</li>
 *   <li>7 {@code View.setAlpha(0f)} on both indicators, whatever their background;</li>
 *   <li>8 complete suppression: mode 7 plus each indicator's own compositor blur region
 *       (BackgroundBlurDrawable alpha 0, through {@link NativeRegionLease});</li>
 *   <li>9 the indicators' compositor blur regions only, view alpha untouched.</li>
 * </ul>
 *
 * <p>Why 8 and 9 exist: E1 shows that while the frame is GONE each indicator carries the inflated
 * RippleDrawable, but once Samsung shows it the background is a {@code BackgroundBlurDrawable}
 * (alpha 8..127, following the view's 0..0.5 fade). Modes 1-4 then lose eligibility, and a clip
 * (modes 5/6) or a view alpha (mode 7) does not limit what SurfaceFlinger blurs for that region:
 * the region's own alpha does. Mode 7 alone is therefore not conclusive if the capsule stays.</p>
 *
 * <p>Lifecycle (revised after the first DZIF run, where mode 5 logged "applied" with no clip and the
 * frame detached 16 ms later, which disarmed the experiment for good): the pre-draw listener and
 * the attach listener live on the <b>CardStackView</b>, the view LiveCardHooks classifies, never on
 * the transient indicator frame. Before every frame the three indicator views are resolved again
 * from the stack's parent (identity checks first), replaced views or drawables have their old
 * leases restored, and the mode is re-read (cached 750 ms), so {@code stackshadow=0} restores
 * without waiting for a swipe. A stack detach suspends (restores) the leases and keeps the request;
 * the next attach resumes it. Mode 5 is applied only once the frame has a laid-out size, and the
 * log separates {@code state=requested} (pending, with the reason) from {@code state=effective}.
 * A steady frame allocates nothing, invalidates nothing and logs nothing.</p>
 *
 * <p>Only the three views with those exact resource names are touched, and only while a glass card
 * of this stack is shown. Drawables are changed through a ColorFilter on the non-mask layer (the
 * mask, outline, alpha, visibility, layout and touch stay native); clips use View.setClipBounds.
 * The previous filter and clip are saved and put back exactly. Main thread only.</p>
 */
final class LiveStackShadowExperiment {
    static final String PROPERTY = "debug.oulg.stackshadow";
    static final String FRAME = "ongoing_card_bottom_shadow_frame";
    static final String SHADOW1 = "ongoing_card_bottom_shadow1_view";
    static final String SHADOW2 = "ongoing_card_bottom_shadow2_view";
    static final int MODE_OFF = 0, MODE_TINT = 1, MODE_HIDE_1 = 2, MODE_HIDE_2 = 3,
            MODE_HIDE_BOTH = 4, MODE_CLIP_FRAME = 5, MODE_CLIP_VIEWS = 6,
            MODE_ALPHA = 7, MODE_SUPPRESS_ALL = 8, MODE_REGION = 9;
    static final int ATTRIBUTION_COLOR = 0xffff00ff;
    /** Package-visible so tests compare by identity (PorterDuffColorFilter.getColor is hidden API). */
    static final ColorFilter MAGENTA = new PorterDuffColorFilter(ATTRIBUTION_COLOR, PorterDuff.Mode.SRC_IN);
    static final ColorFilter CLEAR = new PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN);
    private static final long PROPERTY_TTL_MS = 750;

    /** Why a requested mode is not effective yet (logged as {@code pending=}). */
    static final String PENDING_NONE = "-", PENDING_NO_FRAME = "noFrame",
            PENDING_NOT_LAID_OUT = "frameNotLaidOut", PENDING_NO_INDICATOR = "noIndicator",
            PENDING_NO_GEOMETRY = "noGeometry", PENDING_DETACHED = "detached",
            PENDING_FILTER_REJECTED = "filterRejected", PENDING_NOT_SHOWN = "notShown",
            PENDING_REGION_NOT_BLUR = "regionNotBlur", PENDING_NOT_SUPPRESSED = "notSuppressed";

    private final Function<View, String> names;
    private final IntSupplier modes;

    LiveStackShadowExperiment() { this(LiveStackShadowExperiment::resourceName, LiveStackShadowExperiment::propertyMode); }
    LiveStackShadowExperiment(Function<View, String> names, IntSupplier modes) {
        this.names = names;
        this.modes = modes;
    }

    // ---- leases ------------------------------------------------------------------------------

    /** A drawable filter this class installed, with the native one to put back. */
    private static final class FilterLease {
        WeakReference<View> view = new WeakReference<>(null);
        WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
        WeakReference<Drawable> layer = new WeakReference<>(null);
        ColorFilter installed, nativeFilter;
        /** Why the last apply was refused (constant), or null; plus how often the drawable was swapped. */
        String reject;
        int replacements;

        /** True when {@code filter} is in place on v's current content layer after the call. */
        boolean apply(View v, ColorFilter filter) {
            if (filter == null || v == null) { restore(); reject = REJECT_NO_VIEW; return false; }
            Drawable bg = v.getBackground();
            // Loss of eligibility (not a swap): DZIF shows the indicator with a BackgroundBlurDrawable.
            if (bg == null) { restore(); reject = REJECT_NO_BACKGROUND; return false; }
            if (!(bg instanceof RippleDrawable)) { restore(); reject = REJECT_NOT_RIPPLE; return false; }
            RippleDrawable r = (RippleDrawable) bg;
            Drawable content = ripple.get() == r && view.get() == v ? layer.get() : null;
            if (content != null && installed == filter && content.getColorFilter() == filter) { reject = null; return true; }
            if (content == null) {
                // A swapped ripple (same view) is a replacement, not a loss of eligibility.
                if (view.get() == v && ripple.get() != null && ripple.get() != r) replacements++;
                // New view or new background: put the old one back exactly first.
                restore();
                // Two inflated indicators may share ConstantState: isolate this one first.
                if (r.mutate() != r) { reject = REJECT_MUTATE; return false; }
                content = contentLayer(r);
                if (content == null) { reject = REJECT_NO_CONTENT; return false; }
                nativeFilter = content.getColorFilter();
                view = new WeakReference<>(v);
                ripple = new WeakReference<>(r);
                layer = new WeakReference<>(content);
            } else if (content.getColorFilter() != installed) {
                // Samsung wrote a filter since our last pass: that is the native value now.
                nativeFilter = content.getColorFilter();
            }
            installed = filter;
            content.setColorFilter(filter);
            v.invalidate();
            reject = null;
            return true;
        }

        void restore() {
            Drawable content = layer.get();
            if (content != null && installed != null && content.getColorFilter() == installed) {
                content.setColorFilter(nativeFilter);
                View v = view.get();
                if (v != null) v.invalidate();
            }
            view.clear(); ripple.clear(); layer.clear();
            installed = null; nativeFilter = null;
        }

        boolean activeOn(View v) { return installed != null && v != null && view.get() == v && layer.get() != null; }
        boolean active() { return installed != null && layer.get() != null; }
    }

    static final String REJECT_NO_VIEW = "noView", REJECT_NO_BACKGROUND = "noBackground",
            REJECT_NOT_RIPPLE = "notRipple", REJECT_MUTATE = "mutateReturnedOther",
            REJECT_NO_CONTENT = "noContentLayer";

    /**
     * A View.setAlpha(0f) this class wrote, with Samsung's latest value to put back. Samsung animates
     * the indicators' alpha (0 -> 0.5) when it shows them: a value it writes after ours is taken as
     * the new native value and zeroed again before that frame draws (pre-draw runs after animation
     * callbacks), once per Samsung write, never on a steady frame.
     */
    static final class AlphaLease {
        private WeakReference<View> view = new WeakReference<>(null);
        float nativeAlpha = 1f;
        boolean active;
        int rewrites;

        boolean apply(View v) {
            if (v == null) { restore(); return false; }
            if (view.get() != v) {
                restore();
                view = new WeakReference<>(v);
                nativeAlpha = v.getAlpha();
                active = true;
                rewrites = 0;
                if (nativeAlpha != 0f) v.setAlpha(0f);
                return true;
            }
            float a = v.getAlpha();
            if (a != 0f) {
                nativeAlpha = a;
                rewrites++;
                v.setAlpha(0f);
            }
            return true;
        }

        void restore() {
            View v = view.get();
            if (v != null && active && v.getAlpha() == 0f) v.setAlpha(nativeAlpha);
            view.clear();
            active = false;
        }

        boolean activeOn(View v) { return active && v != null && view.get() == v; }
    }

    /** The indicator's own compositor region, suspended through the tested NativeRegionLease. */
    static final class RegionLease {
        private final NativeRegionLease lease = new NativeRegionLease();
        private WeakReference<View> view = new WeakReference<>(null);
        boolean held;

        /** True when v's background is a native blur region whose alpha is 0 after the call. */
        boolean apply(View v) {
            if (v == null) { restore(); return false; }
            if (view.get() != v) { restore(); view = new WeakReference<>(v); }
            lease.sync(v, true);
            held = true;
            Drawable d = v.getBackground();
            return nativeBlur(d) && d.getAlpha() == 0;
        }

        void restore() {
            if (held) lease.restore();
            held = false;
            view.clear();
        }
    }

    static boolean nativeBlur(Drawable d) {
        return d != null && !(d instanceof io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable)
                && d.getClass().getName().contains("BlurDrawable");
    }

    /** A clip this class installed, with the native one to put back. Allocation-free when steady. */
    static final class ClipLease {
        private WeakReference<View> view = new WeakReference<>(null);
        private final Rect nativeClip = new Rect();
        private boolean hadNative;
        final Rect installed = new Rect();
        private final Rect current = new Rect();
        boolean active;

        /** True when {@code clip} is in place on v after the call. */
        boolean apply(View v, Rect clip) {
            if (v == null || clip == null) { restore(); return false; }
            if (view.get() != v) {
                restore();
                view = new WeakReference<>(v);
                hadNative = v.getClipBounds(nativeClip);
                active = false;
            }
            boolean has = v.getClipBounds(current);
            if (active) {
                if (has && current.equals(installed) && installed.equals(clip)) return true;
                if (!has || !current.equals(installed)) {
                    // Samsung changed the clip since our last pass: keep its value for the restore.
                    hadNative = has;
                    if (has) nativeClip.set(current);
                }
            }
            installed.set(clip);
            active = true;
            v.setClipBounds(installed); // View copies the rect.
            return true;
        }

        void restore() {
            View v = view.get();
            if (v != null && active && v.getClipBounds(current) && current.equals(installed))
                v.setClipBounds(hadNative ? nativeClip : null);
            view.clear();
            hadNative = false;
            active = false;
        }

        boolean activeOn(View v) { return active && v != null && view.get() == v; }
    }

    // ---- state -------------------------------------------------------------------------------

    private WeakReference<View> stack = new WeakReference<>(null);
    private WeakReference<View> frame = new WeakReference<>(null);
    private WeakReference<View> shadow1 = new WeakReference<>(null);
    private WeakReference<View> shadow2 = new WeakReference<>(null);
    private final FilterLease filter1 = new FilterLease(), filter2 = new FilterLease();
    private final ClipLease frameClip = new ClipLease(), clip1 = new ClipLease(), clip2 = new ClipLease();
    private final AlphaLease alpha1 = new AlphaLease(), alpha2 = new AlphaLease();
    private final RegionLease region1 = new RegionLease(), region2 = new RegionLease();
    private final int[] xy = new int[2];
    private final Rect frameRect = new Rect(), rect1 = new Rect(), rect2 = new Rect();
    private ViewTreeObserver observer;
    /** A request is held for {@link #stack}: listeners are installed on it. */
    private boolean armed;
    private boolean suspended;
    private int mode = MODE_OFF;
    private String pending = PENDING_NONE;
    private boolean lastEffective;

    // Last logged state, compared field by field so a steady frame builds no string.
    private int loggedMode = -1, loggedFrame, loggedW = -1, loggedH = -1, loggedLeases = -1;
    private int loggedV1 = -1, loggedV2 = -1, loggedBg1 = -1, loggedBg2 = -1, loggedFv = -2;
    private String loggedState, loggedPending, loggedReject1, loggedReject2;
    private final Rect loggedFrameClip = new Rect(), loggedClip1 = new Rect(), loggedClip2 = new Rect();

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> { onPreDraw(); return true; };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { resume(v); }
        @Override public void onViewDetachedFromWindow(View v) { suspend(); }
    };

    int mode() { return mode; }
    /** Test accessors: the last pass's outcome and the filter refusal reason of indicator 1 or 2. */
    boolean effective() { return lastEffective; }
    String pending() { return pending; }
    String filterReject(int which) { return which == 1 ? filter1.reject : filter2.reject; }

    /** From LiveCardHooks.syncStackFills: glass is shown on a card of this stack or not. */
    void sync(View cardStack, boolean glassPresent) {
        if (cardStack == null) return;
        int wanted = glassPresent ? modes.getAsInt() : MODE_OFF;
        View current = stack.get();
        if (current != null && current != cardStack) {
            // Another stack: it only takes over when it actually requests something.
            if (wanted == MODE_OFF) return;
            restore("relocate");
        }
        if (wanted == MODE_OFF) { restore(glassPresent ? "off" : "noGlass"); return; }
        stack = new WeakReference<>(cardStack);
        arm(cardStack);
        setMode(wanted);
        reapply();
    }

    private void setMode(int wanted) {
        if (wanted == mode) return;
        restoreLeases();
        mode = wanted;
    }

    private void arm(View cardStack) {
        if (!armed) {
            cardStack.addOnAttachStateChangeListener(attach);
            armed = true;
        }
        suspended = !cardStack.isAttachedToWindow();
        observe(cardStack);
    }

    /** The window's current observer. A detached view's floating observer is merged on attach. */
    private void observe(View cardStack) {
        ViewTreeObserver o = cardStack.getViewTreeObserver();
        if (o == observer && o.isAlive()) return;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
        if (!o.isAlive()) return;
        // A floating observer merged on attach may already carry the listener: never twice.
        o.removeOnPreDrawListener(preDraw);
        o.addOnPreDrawListener(preDraw);
        observer = o;
    }

    private void resume(View cardStack) {
        if (!armed || cardStack != stack.get()) return;
        suspended = false;
        observe(cardStack);
        reapply();
    }

    private void suspend() {
        // The views are leaving the window: put back what we changed, keep the request.
        restoreLeases();
        lastEffective = false;
        suspended = true;
        pending = PENDING_DETACHED;
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
        log("suspended");
    }

    private void onPreDraw() {
        View s = stack.get();
        if (s == null) { restore("lost"); return; }
        int wanted = modes.getAsInt();
        if (wanted == MODE_OFF) { restore("off"); return; }
        setMode(wanted);
        reapply();
    }

    /** Resolves the current views and applies the mode; does nothing new on a steady frame. */
    private void reapply() {
        View s = stack.get();
        if (s == null || mode == MODE_OFF) return;
        if (suspended || !s.isAttachedToWindow()) { pending = PENDING_DETACHED; lastEffective = false; log("requested"); return; }
        try {
            resolve(s);
            View f = frame.get(), s1 = shadow1.get(), s2 = shadow2.get();
            boolean effective;
            if (f == null) {
                restoreLeases();
                effective = false;
                pending = PENDING_NO_FRAME;
            } else switch (mode) {
                case MODE_TINT: effective = filters(s1, MAGENTA, s2, MAGENTA); break;
                case MODE_HIDE_1: effective = filters(s1, CLEAR, null, null); break;
                case MODE_HIDE_2: effective = filters(null, null, s2, CLEAR); break;
                case MODE_HIDE_BOTH: effective = filters(s1, CLEAR, s2, CLEAR); break;
                case MODE_CLIP_FRAME: effective = clipFrame(f); break;
                case MODE_CLIP_VIEWS: effective = clipViews(s1, s2); break;
                case MODE_ALPHA: effective = suppress(s1, s2, true, false); break;
                case MODE_SUPPRESS_ALL: effective = suppress(s1, s2, true, true); break;
                case MODE_REGION: effective = suppress(s1, s2, false, true); break;
                default: restoreLeases(); effective = false; pending = PENDING_NONE; break;
            }
            lastEffective = effective;
            log(effective ? "effective" : "requested");
        } catch (RuntimeException e) {
            restore("error:" + e.getClass().getSimpleName());
        }
    }

    private boolean filters(View a, ColorFilter fa, View b, ColorFilter fb) {
        frameClip.restore(); clip1.restore(); clip2.restore(); releaseSuppression();
        boolean okA = true, okB = true;
        if (fa == null) filter1.restore(); else okA = a != null && filter1.apply(a, fa);
        if (fb == null) filter2.restore(); else okB = b != null && filter2.apply(b, fb);
        boolean ok = okA && okB;
        pending = ok ? PENDING_NONE : (fa != null && a == null) || (fb != null && b == null)
                ? PENDING_NO_INDICATOR : PENDING_FILTER_REJECTED;
        return ok;
    }

    private void releaseSuppression() { alpha1.restore(); alpha2.restore(); region1.restore(); region2.restore(); }

    /**
     * Modes 7-9. Effective only when both indicators exist, are shown (view and ancestors VISIBLE,
     * attached), laid out, and suppressed at this pre-draw: a GONE frame is never a success.
     */
    private boolean suppress(View a, View b, boolean viewAlpha, boolean region) {
        filter1.restore(); filter2.restore(); frameClip.restore(); clip1.restore(); clip2.restore();
        if (!viewAlpha) { alpha1.restore(); alpha2.restore(); }
        if (!region) { region1.restore(); region2.restore(); }
        boolean ok1 = a != null, ok2 = b != null;
        if (viewAlpha) {
            ok1 &= alpha1.apply(a) && a.getAlpha() == 0f;
            ok2 &= alpha2.apply(b) && b.getAlpha() == 0f;
        }
        if (region) {
            ok1 &= region1.apply(a);
            ok2 &= region2.apply(b);
        }
        if (a == null || b == null) pending = PENDING_NO_INDICATOR;
        else if (!a.isShown() || !b.isShown()) pending = PENDING_NOT_SHOWN;
        else if (a.getWidth() <= 0 || a.getHeight() <= 0 || b.getWidth() <= 0 || b.getHeight() <= 0) pending = PENDING_NOT_LAID_OUT;
        else if (region && (!nativeBlur(a.getBackground()) || !nativeBlur(b.getBackground()))) pending = PENDING_REGION_NOT_BLUR;
        else if (!ok1 || !ok2) pending = PENDING_NOT_SUPPRESSED;
        else pending = PENDING_NONE;
        return pending == PENDING_NONE;
    }

    private boolean clipFrame(View f) {
        filter1.restore(); filter2.restore(); clip1.restore(); clip2.restore(); releaseSuppression();
        if (f.getWidth() <= 0 || f.getHeight() <= 0) {
            // An installed clip stays through a transient 0x0 layout of the same frame.
            if (frameClip.activeOn(f)) { pending = PENDING_NONE; return true; }
            // Not laid out yet: nothing to clip to. Retried on the next frame.
            frameClip.restore();
            pending = PENDING_NOT_LAID_OUT;
            return false;
        }
        frameRect.set(0, 0, f.getWidth(), f.getHeight());
        boolean ok = frameClip.apply(f, frameRect);
        pending = ok ? PENDING_NONE : PENDING_NOT_LAID_OUT;
        return ok;
    }

    private boolean clipViews(View a, View b) {
        filter1.restore(); filter2.restore(); frameClip.restore(); releaseSuppression();
        boolean okA = a != null && belowParentTop(a, rect1) && clip1.apply(a, rect1);
        if (!okA) clip1.restore();
        boolean okB = b != null && belowParentTop(b, rect2) && clip2.apply(b, rect2);
        if (!okB) clip2.restore();
        boolean ok = okA && okB;
        pending = ok ? PENDING_NONE : (a == null || b == null ? PENDING_NO_INDICATOR : PENDING_NO_GEOMETRY);
        return ok;
    }

    /**
     * The three named views, found from the stack's own parent, never by shape or size. When any
     * of them is a new instance, every lease is restored on the old views before the new ones are
     * touched.
     */
    private void resolve(View cardStack) {
        View f = frame.get(), s1 = shadow1.get(), s2 = shadow2.get();
        ViewParent p = cardStack.getParent();
        if (f != null && f.getParent() == p && p != null
                && (s1 == null || s1.getParent() == f) && (s2 == null || s2.getParent() == f)
                && childCountMatches(f, s1, s2)) return;
        View nf = null, n1 = null, n2 = null;
        if (p instanceof ViewGroup) {
            ViewGroup layout = (ViewGroup) p;
            for (int i = 0; i < layout.getChildCount(); i++) {
                View c = layout.getChildAt(i);
                if (c != cardStack && FRAME.equals(names.apply(c))) { nf = c; break; }
            }
        }
        if (nf instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) nf;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String n = names.apply(c);
                if (SHADOW1.equals(n)) n1 = c;
                else if (SHADOW2.equals(n)) n2 = c;
            }
        } else nf = null;
        if (nf != f || n1 != s1 || n2 != s2) restoreLeases();
        frame = new WeakReference<>(nf);
        shadow1 = new WeakReference<>(n1);
        shadow2 = new WeakReference<>(n2);
    }

    /** A cached indicator that went missing while the frame stayed: resolve again. */
    private static boolean childCountMatches(View f, View s1, View s2) {
        if (!(f instanceof ViewGroup)) return false;
        int expected = (s1 == null ? 0 : 1) + (s2 == null ? 0 : 1);
        return expected == 2 || ((ViewGroup) f).getChildCount() <= expected;
    }

    /**
     * The part of an indicator that lies at or below its parent's top edge (the stack's bottom),
     * in the indicator's own coordinates, from its live top, translation, pivot and scale.
     * False when it cannot be computed; the native clip is then kept.
     */
    static boolean belowParentTop(View v, Rect out) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        float sy = v.getScaleY();
        if (sy <= 0f || Float.isNaN(sy)) return false;
        float pivot = v.getPivotY();
        // parentY(localY) = top + translationY + pivot + (localY - pivot) * scaleY
        float origin = v.getTop() + v.getTranslationY() + pivot;
        float localTop = pivot - origin / sy;
        // Float scale (0.84f) puts an exact boundary a hair above the integer: tolerate it.
        int top = (int) Math.ceil(localTop - 0.01f);
        top = Math.max(0, Math.min(v.getHeight(), top));
        out.set(0, top, v.getWidth(), v.getHeight());
        return true;
    }
    /** Test convenience: a new Rect, or null. */
    static Rect belowParentTop(View v) {
        Rect r = new Rect();
        return belowParentTop(v, r) ? r : null;
    }

    private void restoreLeases() {
        filter1.restore(); filter2.restore();
        frameClip.restore(); clip1.restore(); clip2.restore();
        releaseSuppression();
    }

    /** Everything back to native and every listener removed. */
    void restore(String reason) {
        boolean had = armed || mode != MODE_OFF || filter1.active() || filter2.active()
                || frameClip.active || clip1.active || clip2.active
                || alpha1.active || alpha2.active || region1.held || region2.held;
        restoreLeases();
        View s = stack.get();
        if (s != null && armed) s.removeOnAttachStateChangeListener(attach);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
        armed = false;
        suspended = false;
        lastEffective = false;
        if (had) {
            pending = PENDING_NONE;
            log("restored:" + reason);
        }
        mode = MODE_OFF;
        stack.clear(); frame.clear(); shadow1.clear(); shadow2.clear();
    }

    // ---- telemetry ---------------------------------------------------------------------------

    private int leaseBits() {
        return (filter1.active() ? 1 : 0) | (filter2.active() ? 2 : 0) | (frameClip.active ? 4 : 0)
                | (clip1.active ? 8 : 0) | (clip2.active ? 16 : 0) | (alpha1.active ? 32 : 0)
                | (alpha2.active ? 64 : 0) | (region1.held ? 128 : 0) | (region2.held ? 256 : 0);
    }

    /** Visibility, layout and suppression of one indicator, packed for the change check. */
    private static int viewBits(View v) {
        if (v == null) return 0;
        Drawable d = v.getBackground();
        return 1 | (v.isShown() ? 2 : 0) | (v.getWidth() > 0 && v.getHeight() > 0 ? 4 : 0)
                | (v.getAlpha() == 0f ? 8 : 0) | (nativeBlur(d) ? 16 : 0)
                | (d != null && d.getAlpha() == 0 ? 32 : 0) | (v.getVisibility() << 8);
    }

    private static int bgId(View v) {
        Drawable d = v == null ? null : v.getBackground();
        return d == null ? 0 : System.identityHashCode(d);
    }

    /**
     * Logs only when mode, state, pending reason, frame identity/size, a lease, an installed clip,
     * an indicator's background identity, a refusal reason, or an indicator's shown / laid-out /
     * suppressed status changed. Animated alpha values alone never log.
     */
    private void log(String state) {
        View f = frame.get(), a = shadow1.get(), b = shadow2.get();
        int fid = f == null ? 0 : System.identityHashCode(f);
        int w = f == null ? -1 : f.getWidth(), h = f == null ? -1 : f.getHeight();
        int bits = leaseBits();
        int v1 = viewBits(a), v2 = viewBits(b), bg1 = bgId(a), bg2 = bgId(b);
        int fv = f == null ? -1 : (f.getVisibility() | (f.isShown() ? 16 : 0));
        // Installed clip geometry too: mode 6 recomputes clips from transforms that can change
        // while sizes, leases and state stay the same.
        if (mode == loggedMode && fid == loggedFrame && w == loggedW && h == loggedH && bits == loggedLeases
                && v1 == loggedV1 && v2 == loggedV2 && bg1 == loggedBg1 && bg2 == loggedBg2 && fv == loggedFv
                && state.equals(loggedState) && pending.equals(loggedPending)
                && java.util.Objects.equals(filter1.reject, loggedReject1) && java.util.Objects.equals(filter2.reject, loggedReject2)
                && (!frameClip.active || frameClip.installed.equals(loggedFrameClip))
                && (!clip1.active || clip1.installed.equals(loggedClip1))
                && (!clip2.active || clip2.installed.equals(loggedClip2))) return;
        loggedMode = mode; loggedFrame = fid; loggedW = w; loggedH = h; loggedLeases = bits;
        loggedV1 = v1; loggedV2 = v2; loggedBg1 = bg1; loggedBg2 = bg2; loggedFv = fv;
        loggedState = state; loggedPending = pending;
        loggedReject1 = filter1.reject; loggedReject2 = filter2.reject;
        loggedFrameClip.set(frameClip.installed);
        loggedClip1.set(clip1.installed);
        loggedClip2.set(clip2.installed);
        Probe.log("LIVE_STACK_SHADOW_E2", "mode=" + mode + " state=" + state + " pending=" + pending
                + " frame=" + id(f) + " frameWh=" + (f == null ? "-" : w + "x" + h)
                + " frameVis=" + (f == null ? "-" : String.valueOf(f.getVisibility()))
                + " frameShown=" + (f != null && f.isShown())
                + " frameClip=" + (frameClip.active ? frameClip.installed.toShortString() : "-")
                + " sh1{" + describe(a, filter1, alpha1, region1, clip1) + "}"
                + " sh2{" + describe(b, filter2, alpha2, region2, clip2) + "}"
                + " stack=" + id(stack.get()) + " attached=" + (stack.get() != null && stack.get().isAttachedToWindow()));
    }

    /** Only called when a line is logged: identity, visibility, geometry and every lease of one indicator. */
    private String describe(View v, FilterLease filter, AlphaLease alpha, RegionLease region, ClipLease clip) {
        if (v == null) return "null";
        Drawable d = v.getBackground();
        v.getLocationOnScreen(xy);
        StringBuilder sb = new StringBuilder(160)
                .append("id=").append(id(v))
                .append(" vis=").append(v.getVisibility()).append(" shown=").append(v.isShown())
                .append(" wh=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" screenY=").append(xy[1]).append("..").append(xy[1] + Math.round(v.getHeight() * v.getScaleY()))
                .append(" sy=").append(v.getScaleY()).append(" ty=").append(v.getTranslationY())
                .append(" alpha=").append(v.getAlpha())
                .append(" bg=").append(d == null ? "null" : d.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(d)))
                .append(" bgAlpha=").append(d == null ? -1 : d.getAlpha())
                .append(" filter=").append(filter.active())
                .append(" reject=").append(filter.reject == null ? "-" : filter.reject)
                .append(" replaced=").append(filter.replacements)
                .append(" alphaLease=").append(alpha.active ? "native" + alpha.nativeAlpha + "/rewrites" + alpha.rewrites : "-")
                .append(" region=").append(region.held)
                .append(" clip=").append(clip.active ? clip.installed.toShortString() : "-");
        if (filter.reject == REJECT_NO_CONTENT && d instanceof RippleDrawable) {
            RippleDrawable r = (RippleDrawable) d;
            sb.append(" layers=").append(r.getNumberOfLayers());
            for (int i = 0; i < r.getNumberOfLayers(); i++) {
                Drawable l = r.getDrawable(i);
                sb.append(' ').append(i).append(':').append(r.getId(i) == android.R.id.mask ? "mask" : "-")
                        .append(':').append(l == null ? "null" : l.getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    private static String id(View v) { return v == null ? "null" : Integer.toHexString(System.identityHashCode(v)); }

    static String resourceName(View v) {
        try { return v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId()); }
        catch (RuntimeException e) { return "-"; }
    }

    /** The first non-mask layer of the indicator's RippleDrawable: its {@code #FA3A3A3D} content. */
    static Drawable contentLayer(RippleDrawable r) {
        for (int i = 0; i < r.getNumberOfLayers(); i++)
            if (r.getId(i) != android.R.id.mask) return r.getDrawable(i);
        return null;
    }

    private static long lastRead = -1;
    private static int cached;
    private static Method getter;
    /** debug.oulg.stackshadow, re-read at most every 750 ms (also from the pre-draw listener). */
    static int propertyMode() {
        long now = SystemClock.uptimeMillis();
        if (lastRead >= 0 && now - lastRead < PROPERTY_TTL_MS) return cached;
        lastRead = now;
        int value = MODE_OFF;
        try {
            if (getter == null) getter = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            String s = (String) getter.invoke(null, PROPERTY);
            if (s != null && s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '9') value = s.charAt(0) - '0';
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            value = MODE_OFF;
        }
        cached = value;
        return value;
    }
}

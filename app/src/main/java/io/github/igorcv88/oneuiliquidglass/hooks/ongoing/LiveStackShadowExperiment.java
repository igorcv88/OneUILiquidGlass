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
 *   <li>6 option A: clip each indicator to the part below the frame's top, from its transform.</li>
 * </ul>
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
            MODE_HIDE_BOTH = 4, MODE_CLIP_FRAME = 5, MODE_CLIP_VIEWS = 6;
    static final int ATTRIBUTION_COLOR = 0xffff00ff;
    /** Package-visible so tests compare by identity (PorterDuffColorFilter.getColor is hidden API). */
    static final ColorFilter MAGENTA = new PorterDuffColorFilter(ATTRIBUTION_COLOR, PorterDuff.Mode.SRC_IN);
    static final ColorFilter CLEAR = new PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN);
    private static final long PROPERTY_TTL_MS = 750;

    /** Why a requested mode is not effective yet (logged as {@code pending=}). */
    static final String PENDING_NONE = "-", PENDING_NO_FRAME = "noFrame",
            PENDING_NOT_LAID_OUT = "frameNotLaidOut", PENDING_NO_INDICATOR = "noIndicator",
            PENDING_NO_GEOMETRY = "noGeometry", PENDING_DETACHED = "detached";

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

        /** True when {@code filter} is in place on v's current content layer after the call. */
        boolean apply(View v, ColorFilter filter) {
            if (filter == null || v == null) { restore(); return false; }
            Drawable bg = v.getBackground();
            if (!(bg instanceof RippleDrawable)) { restore(); return false; }
            RippleDrawable r = (RippleDrawable) bg;
            Drawable content = ripple.get() == r && view.get() == v ? layer.get() : null;
            if (content != null && installed == filter && content.getColorFilter() == filter) return true;
            if (content == null) {
                // New view or new background: put the old one back exactly first.
                restore();
                // Two inflated indicators may share ConstantState: isolate this one first.
                if (r.mutate() != r) return false;
                content = contentLayer(r);
                if (content == null) return false;
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
    private final Rect frameRect = new Rect(), rect1 = new Rect(), rect2 = new Rect();
    private ViewTreeObserver observer;
    /** A request is held for {@link #stack}: listeners are installed on it. */
    private boolean armed;
    private boolean suspended;
    private int mode = MODE_OFF;
    private String pending = PENDING_NONE;

    // Last logged state, compared field by field so a steady frame builds no string.
    private int loggedMode = -1, loggedFrame, loggedW = -1, loggedH = -1, loggedLeases = -1;
    private String loggedState, loggedPending;
    private final Rect loggedFrameClip = new Rect(), loggedClip1 = new Rect(), loggedClip2 = new Rect();

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> { onPreDraw(); return true; };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { resume(v); }
        @Override public void onViewDetachedFromWindow(View v) { suspend(); }
    };

    int mode() { return mode; }

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
        if (suspended || !s.isAttachedToWindow()) { pending = PENDING_DETACHED; log("requested"); return; }
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
                default: restoreLeases(); effective = false; pending = PENDING_NONE; break;
            }
            log(effective ? "effective" : "requested");
        } catch (RuntimeException e) {
            restore("error:" + e.getClass().getSimpleName());
        }
    }

    private boolean filters(View a, ColorFilter fa, View b, ColorFilter fb) {
        frameClip.restore(); clip1.restore(); clip2.restore();
        boolean okA = true, okB = true;
        if (fa == null) filter1.restore(); else okA = a != null && filter1.apply(a, fa);
        if (fb == null) filter2.restore(); else okB = b != null && filter2.apply(b, fb);
        boolean ok = okA && okB;
        pending = ok ? PENDING_NONE : PENDING_NO_INDICATOR;
        return ok;
    }

    private boolean clipFrame(View f) {
        filter1.restore(); filter2.restore(); clip1.restore(); clip2.restore();
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
        filter1.restore(); filter2.restore(); frameClip.restore();
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
    }

    /** Everything back to native and every listener removed. */
    void restore(String reason) {
        boolean had = armed || mode != MODE_OFF || filter1.active() || filter2.active()
                || frameClip.active || clip1.active || clip2.active;
        restoreLeases();
        View s = stack.get();
        if (s != null && armed) s.removeOnAttachStateChangeListener(attach);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
        armed = false;
        suspended = false;
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
                | (clip1.active ? 8 : 0) | (clip2.active ? 16 : 0);
    }

    /** Logs only when mode, state, pending reason, frame identity/size or a lease changed. */
    private void log(String state) {
        View f = frame.get();
        int fid = f == null ? 0 : System.identityHashCode(f);
        int w = f == null ? -1 : f.getWidth(), h = f == null ? -1 : f.getHeight();
        int bits = leaseBits();
        // Installed clip geometry too: mode 6 recomputes clips from transforms that can change
        // while sizes, leases and state stay the same.
        if (mode == loggedMode && fid == loggedFrame && w == loggedW && h == loggedH && bits == loggedLeases
                && state.equals(loggedState) && pending.equals(loggedPending)
                && (!frameClip.active || frameClip.installed.equals(loggedFrameClip))
                && (!clip1.active || clip1.installed.equals(loggedClip1))
                && (!clip2.active || clip2.installed.equals(loggedClip2))) return;
        loggedMode = mode; loggedFrame = fid; loggedW = w; loggedH = h; loggedLeases = bits;
        loggedState = state; loggedPending = pending;
        loggedFrameClip.set(frameClip.installed);
        loggedClip1.set(clip1.installed);
        loggedClip2.set(clip2.installed);
        Probe.log("LIVE_STACK_SHADOW_E2", "mode=" + mode + " state=" + state + " pending=" + pending
                + " frame=" + id(f) + " frameWh=" + (f == null ? "-" : w + "x" + h)
                + " frameVis=" + (f == null ? "-" : String.valueOf(f.getVisibility()))
                + " frameClip=" + (frameClip.active ? frameClip.installed.toShortString() : "-")
                + " shadow1=" + id(shadow1.get()) + " shadow2=" + id(shadow2.get())
                + " filter1=" + filter1.active() + " filter2=" + filter2.active()
                + " clip1=" + (clip1.active ? clip1.installed.toShortString() : "-")
                + " clip2=" + (clip2.active ? clip2.installed.toShortString() : "-")
                + " stack=" + id(stack.get()) + " attached=" + (stack.get() != null && stack.get().isAttachedToWindow()));
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
            if (s != null && s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '6') value = s.charAt(0) - '0';
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            value = MODE_OFF;
        }
        cached = value;
        return value;
    }
}

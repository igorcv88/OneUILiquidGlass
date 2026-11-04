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
import java.util.Objects;
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

    private final Function<View, String> names;
    private final IntSupplier modes;

    LiveStackShadowExperiment() { this(LiveStackShadowExperiment::resourceName, LiveStackShadowExperiment::propertyMode); }
    LiveStackShadowExperiment(Function<View, String> names, IntSupplier modes) {
        this.names = names;
        this.modes = modes;
    }

    /** A drawable filter this class installed, with the native one to put back. */
    private static final class FilterLease {
        WeakReference<View> view = new WeakReference<>(null);
        WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
        WeakReference<Drawable> layer = new WeakReference<>(null);
        ColorFilter installed, nativeFilter;

        void apply(View v, ColorFilter filter) {
            if (filter == null || v == null) { restore(); return; }
            Drawable bg = v.getBackground();
            if (!(bg instanceof RippleDrawable)) { restore(); return; }
            RippleDrawable r = (RippleDrawable) bg;
            Drawable content = ripple.get() == r ? layer.get() : null;
            if (content == null || installed != filter || content.getColorFilter() != filter) {
                if (ripple.get() != r || content == null) {
                    restore();
                    // Two inflated indicators may share ConstantState: isolate this one first.
                    if (r.mutate() != r) return;
                    content = contentLayer(r);
                    if (content == null) return;
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
            }
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

        boolean active() { return installed != null && layer.get() != null; }
    }

    /** A clip this class installed, with the native one to put back. */
    private static final class ClipLease {
        WeakReference<View> view = new WeakReference<>(null);
        Rect nativeClip;
        final Rect installed = new Rect();
        boolean active;

        void apply(View v, Rect clip) {
            if (v == null || clip == null) { restore(); return; }
            if (view.get() != v) {
                restore();
                view = new WeakReference<>(v);
                nativeClip = v.getClipBounds();
            } else if (active && !installed.equals(v.getClipBounds())) {
                // Samsung changed the clip since our last pass: keep its value for the restore.
                nativeClip = v.getClipBounds();
            }
            if (active && installed.equals(clip) && clip.equals(v.getClipBounds())) return;
            installed.set(clip);
            active = true;
            v.setClipBounds(clip);
        }

        void restore() {
            View v = view.get();
            if (v != null && active && installed.equals(v.getClipBounds())) v.setClipBounds(nativeClip);
            view.clear();
            nativeClip = null;
            active = false;
        }
    }

    private WeakReference<View> stack = new WeakReference<>(null);
    private WeakReference<View> frame = new WeakReference<>(null);
    private WeakReference<View> shadow1 = new WeakReference<>(null);
    private WeakReference<View> shadow2 = new WeakReference<>(null);
    private final FilterLease filter1 = new FilterLease(), filter2 = new FilterLease();
    private final ClipLease frameClip = new ClipLease(), clip1 = new ClipLease(), clip2 = new ClipLease();
    private ViewTreeObserver observer;
    private int mode = MODE_OFF;
    private String lastLog;

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> { reapply(); return true; };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) { restore("detached"); }
    };

    /** From LiveCardHooks.syncStackFills: glass is shown on a card of this stack or not. */
    void sync(View cardStack, boolean glassPresent) {
        int wanted = glassPresent && cardStack != null ? modes.getAsInt() : MODE_OFF;
        if (wanted == MODE_OFF) {
            if (cardStack == null || cardStack == stack.get()) restore(glassPresent ? "off" : "noGlass");
            return;
        }
        if (cardStack != stack.get()) restore("relocate");
        View previousFrame = frame.get();
        if (!locate(cardStack)) {
            restore("notFound");
            log("notFound", cardStack);
            return;
        }
        if (stack.get() != cardStack || frame.get() != previousFrame || observer == null) {
            stack = new WeakReference<>(cardStack);
            listen(cardStack);
        }
        if (wanted != mode) restoreLeases();
        mode = wanted;
        reapply();
    }

    int mode() { return mode; }

    /** The three named views, found from the stack's own parent, never by shape or size. */
    private boolean locate(View cardStack) {
        View f = frame.get(), s1 = shadow1.get(), s2 = shadow2.get();
        if (f != null && f.getParent() == cardStack.getParent()
                && s1 != null && s1.getParent() == f && s2 != null && s2.getParent() == f) return true;
        ViewParent p = cardStack.getParent();
        if (!(p instanceof ViewGroup)) return false;
        ViewGroup layout = (ViewGroup) p;
        View foundFrame = null;
        for (int i = 0; i < layout.getChildCount(); i++) {
            View c = layout.getChildAt(i);
            if (c != cardStack && FRAME.equals(names.apply(c))) { foundFrame = c; break; }
        }
        if (!(foundFrame instanceof ViewGroup)) return false;
        View a = null, b = null;
        ViewGroup g = (ViewGroup) foundFrame;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            String n = names.apply(c);
            if (SHADOW1.equals(n)) a = c;
            else if (SHADOW2.equals(n)) b = c;
        }
        if (a == null && b == null) return false;
        frame = new WeakReference<>(foundFrame);
        shadow1 = new WeakReference<>(a);
        shadow2 = new WeakReference<>(b);
        return true;
    }

    private void listen(View cardStack) {
        removeListeners();
        View f = frame.get();
        if (f == null) return;
        listenedFrame = new WeakReference<>(f);
        f.addOnAttachStateChangeListener(attach);
        ViewTreeObserver o = f.getViewTreeObserver();
        if (o.isAlive()) { o.addOnPreDrawListener(preDraw); observer = o; }
    }

    private WeakReference<View> listenedFrame = new WeakReference<>(null);
    private void removeListeners() {
        View f = listenedFrame.get();
        if (f != null) f.removeOnAttachStateChangeListener(attach);
        listenedFrame.clear();
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        observer = null;
    }

    /** Re-asserted before each frame: Samsung may rebind backgrounds or relayout the frame. */
    private void reapply() {
        View f = frame.get(), s1 = shadow1.get(), s2 = shadow2.get();
        if (f == null) { restore("lost"); return; }
        try {
            switch (mode) {
                case MODE_TINT: filter1.apply(s1, MAGENTA); filter2.apply(s2, MAGENTA); break;
                case MODE_HIDE_1: filter1.apply(s1, CLEAR); filter2.restore(); break;
                case MODE_HIDE_2: filter1.restore(); filter2.apply(s2, CLEAR); break;
                case MODE_HIDE_BOTH: filter1.apply(s1, CLEAR); filter2.apply(s2, CLEAR); break;
                case MODE_CLIP_FRAME:
                    if (f.getWidth() > 0 && f.getHeight() > 0) frameClip.apply(f, new Rect(0, 0, f.getWidth(), f.getHeight()));
                    break;
                case MODE_CLIP_VIEWS:
                    clip1.apply(s1, belowParentTop(s1));
                    clip2.apply(s2, belowParentTop(s2));
                    break;
                default: restoreLeases(); break;
            }
        } catch (RuntimeException e) {
            restore("error:" + e.getClass().getSimpleName());
            return;
        }
        log("applied", stack.get());
    }

    /**
     * The part of an indicator that lies at or below its parent's top edge (the stack's bottom),
     * in the indicator's own coordinates, from its live top, translation, pivot and scale.
     * Null when it cannot be computed; the native clip is then kept.
     */
    static Rect belowParentTop(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        float sy = v.getScaleY();
        if (sy <= 0f || Float.isNaN(sy)) return null;
        float pivot = v.getPivotY();
        // parentY(localY) = top + translationY + pivot + (localY - pivot) * scaleY
        float origin = v.getTop() + v.getTranslationY() + pivot;
        float localTop = pivot - origin / sy;
        // Float scale (0.84f) puts an exact boundary a hair above the integer: tolerate it.
        int top = (int) Math.ceil(localTop - 0.01f);
        top = Math.max(0, Math.min(v.getHeight(), top));
        return new Rect(0, top, v.getWidth(), v.getHeight());
    }

    private void restoreLeases() {
        filter1.restore(); filter2.restore();
        frameClip.restore(); clip1.restore(); clip2.restore();
    }

    void restore(String reason) {
        boolean had = mode != MODE_OFF || filter1.active() || filter2.active()
                || frameClip.active || clip1.active || clip2.active;
        restoreLeases();
        removeListeners();
        View s = stack.get();
        mode = MODE_OFF;
        stack.clear(); frame.clear(); shadow1.clear(); shadow2.clear();
        if (had) log("restored:" + reason, s);
    }

    private void log(String state, View cardStack) {
        String line = "mode=" + mode + " state=" + state
                + " frame=" + id(frame.get()) + " shadow1=" + id(shadow1.get()) + " shadow2=" + id(shadow2.get())
                + " filter1=" + filter1.active() + " filter2=" + filter2.active()
                + " frameClip=" + (frameClip.active ? frameClip.installed.toShortString() : "-")
                + " clip1=" + (clip1.active ? clip1.installed.toShortString() : "-")
                + " clip2=" + (clip2.active ? clip2.installed.toShortString() : "-");
        if (Objects.equals(line, lastLog)) return;
        lastLog = line;
        Probe.log("LIVE_STACK_SHADOW_E2", line + " stack=" + id(cardStack));
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
    /** debug.oulg.stackshadow, re-read at most every 750 ms and only from stack writers. */
    static int propertyMode() {
        long now = SystemClock.uptimeMillis();
        if (lastRead >= 0 && now - lastRead < PROPERTY_TTL_MS) return cached;
        lastRead = now;
        int value = MODE_OFF;
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            String s = (String) get.invoke(null, PROPERTY);
            if (s != null && s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '6') value = s.charAt(0) - '0';
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            value = MODE_OFF;
        }
        cached = value;
        return value;
    }
}

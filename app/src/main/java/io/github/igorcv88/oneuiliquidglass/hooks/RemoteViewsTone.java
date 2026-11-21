package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.graphics.BlendMode;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Adapts publisher tint properties on typed controls over the card's glass. Main thread only. */
final class RemoteViewsTone {
    private RemoteViewsTone() {}

    private static final int PROGRESS = 0;
    private static final int ACTION = NotificationProgressTone.CHANNELS;
    private static final int UNKNOWN = -1;
    private static final WeakHashMap<View, Slot[]> active = new WeakHashMap<>();
    private static final WeakHashMap<View, Attachment> attachments = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final class Attachment {
        final WeakReference<View> host;
        final WeakReference<View> root;
        final WeakReference<IBinder> window;
        boolean pending;

        Attachment(View view) {
            host = new WeakReference<>(host(view));
            root = new WeakReference<>(view.getRootView());
            window = new WeakReference<>(view.getWindowToken());
        }

        boolean matches(View view) {
            return host.get() != null && host.get() == host(view)
                    && root.get() == view.getRootView()
                    && window.get() != null && window.get() == view.getWindowToken();
        }
    }

    private static View host(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            View ancestor = (View) parent;
            if (Foreground.ownsLiveCardTone.test(ancestor)
                    || ancestor.getClass().getName().endsWith("ExpandableNotificationRow")) return ancestor;
        }
        return null;
    }

    private static final View.OnAttachStateChangeListener lifecycle = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {
            Attachment previous = attachments.get(view);
            if (previous != null && previous.pending
                    && (!previous.matches(view) || !Foreground.customSurface(view) || drift(view))) {
                release(view);
                return;
            }
            attachments.put(view, new Attachment(view));
        }

        @Override public void onViewDetachedFromWindow(View view) {
            Attachment previous = attachments.get(view);
            if (previous == null || previous.pending) return;
            previous.pending = true;
            WeakReference<View> detached = new WeakReference<>(view);
            // Keep the displayed tint across same-message transfers. A later attach starts native.
            main.post(() -> {
                View child = detached.get();
                if (child != null && attachments.get(child) == previous) release(child);
            });
        }
    };

    static boolean owns(View view) { return active.containsKey(view); }

    private static final class Slot {
        final WeakReference<Drawable> drawable;
        final BlendMode mode;
        final ColorStateList nativeTint;
        ColorStateList shown;
        int target;
        boolean restoring;

        Slot(Drawable drawable, BlendMode mode, ColorStateList nativeTint, int target) {
            this.drawable = new WeakReference<>(drawable);
            this.mode = mode;
            this.nativeTint = nativeTint;
            this.shown = nativeTint;
            this.target = target;
        }
    }

    static boolean apply(View view, boolean swap, boolean lightText, boolean animate, InkAnimator.Timeline timeline) {
        if (!(view instanceof ImageView) && !(view instanceof ProgressBar)) {
            if (swap && Probe.trace && view.getClass() == android.widget.FrameLayout.class
                    && view.getBackground() != null) report(view, UNKNOWN, lightText, "painted-container-role-unverified");
            return false;
        }
        int kind = kind(view);
        Slot[] slots = active.get(view);
        if (kind == UNKNOWN && slots == null) {
            if (swap && Probe.trace && (view instanceof ImageView || view instanceof ProgressBar))
                report(view, UNKNOWN, lightText, view instanceof ImageView ? "image-role-unverified" : "progress-role-unverified");
            return false;
        }
        boolean eligible = kind != UNKNOWN && Foreground.customSurface(view);
        if ((!swap || !eligible) && slots == null) {
            if (swap) report(view, kind, lightText, "private-or-unowned-surface");
            return false;
        }
        if (slots == null) {
            slots = new Slot[kind == ACTION ? 1 : NotificationProgressTone.CHANNELS];
        }
        boolean changed = false;
        for (int index = 0; index < slots.length; index++) {
            int channel = slots.length == 1 ? ACTION : index;
            changed |= update(view, slots, index, channel, swap, eligible, lightText, animate, timeline);
        }
        return changed;
    }

    private static int kind(View view) {
        if (view.getClass() == ProgressBar.class && !NotificationProgressTone.template(view)) return PROGRESS;
        if (view.getClass() == ImageButton.class && view.isClickable() && view.hasOnClickListeners()
                && !NotificationIconTreatment.managed((ImageView) view)
                && !ShelfIconTint.owns((ImageView) view)) return ACTION;
        return UNKNOWN;
    }

    private static boolean update(View view, Slot[] slots, int index, int channel, boolean swap, boolean eligible,
                                  boolean lightText, boolean animate, InkAnimator.Timeline timeline) {
        Slot slot = slots[index];
        Drawable drawable = drawable(view, channel);
        BlendMode mode = mode(view, channel);
        boolean changed = false;
        if (slot != null && Probe.trace && tint(view, channel) != slot.shown
                && NotificationProgressTone.same(tint(view, channel), slot.shown))
            report(view, channel, lightText, "equal-tint-different-reference");
        if (slot != null && (tint(view, channel) != slot.shown
                || drawable != slot.drawable.get() || mode != slot.mode)) {
            changed = drop(view, slots, index, channel, slot);
            slot = null;
        }
        ColorStateList nativeTint = slot == null ? tint(view, channel) : slot.nativeTint;
        Integer target = swap && eligible ? target(nativeTint, lightText) : null;
        boolean supported = target == null || slot != null || supported(view, channel, drawable, mode);
        if (!supported) target = null;
        if (target == null) {
            if (slot == null) {
                if (swap && eligible) report(view, channel, lightText, !supported ? "drawable-or-blend-unverified" : tintReason(nativeTint));
                return changed;
            }
            if (slot.restoring && animate) return changed;
            if (!animate || !eligible) return drop(view, slots, index, channel, slot) || changed;
            slot.restoring = true;
            move(view, slots, index, channel, slot, nativeTint.getDefaultColor(), true, timeline, false);
            return true;
        }
        if (slot != null && !slot.restoring && slot.target == target) return changed;
        boolean fresh = slot == null;
        if (fresh) {
            slot = new Slot(drawable, mode, nativeTint, target);
            slots[index] = slot;
            if (!active.containsKey(view)) {
                active.put(view, slots);
                attachments.put(view, new Attachment(view));
                view.addOnAttachStateChangeListener(lifecycle);
            }
        }
        slot.restoring = false;
        slot.target = target;
        move(view, slots, index, channel, slot, target, animate, timeline, fresh);
        return true;
    }

    private static void move(View view, Slot[] slots, int index, int channel, Slot slot, int target,
                             boolean animate, InkAnimator.Timeline timeline, boolean fresh) {
        InkAnimator.run(slot, new InkAnimator.Target() {
            @Override public int shown() {
                // A replaced drawable, mode or publisher tint invalidates the animation before its next write.
                return current(view, channel, slot) ? slot.shown.getDefaultColor() : ~slot.shown.getDefaultColor();
            }
            @Override public void show(int color) {
                if (slot.shown.getDefaultColor() == color) return;
                slot.shown = new ColorStateList(new int[][] { android.util.StateSet.WILD_CARD }, new int[] { color });
                write(view, channel, slot.shown);
            }
        }, target, animate,
                slot.restoring ? () -> drop(view, slots, index, channel, slot) : null,
                () -> drop(view, slots, index, channel, slot), timeline, fresh);
    }

    private static boolean current(View view, int channel, Slot slot) {
        return tint(view, channel) == slot.shown && drawable(view, channel) == slot.drawable.get()
                && mode(view, channel) == slot.mode;
    }

    static boolean drift(View view) {
        Slot[] slots = active.get(view);
        if (slots == null) return false;
        for (int index = 0; index < slots.length; index++) {
            Slot slot = slots[index];
            if (slot != null && !current(view, slots.length == 1 ? ACTION : index, slot)) return true;
        }
        return false;
    }

    static void release(View view) {
        Slot[] slots = active.get(view);
        if (slots == null) return;
        for (int index = 0; index < slots.length; index++) {
            Slot slot = slots[index];
            if (slot != null) drop(view, slots, index, slots.length == 1 ? ACTION : index, slot);
        }
    }

    private static boolean drop(View view, Slot[] slots, int index, int channel, Slot slot) {
        InkAnimator.cancel(slot);
        slots[index] = null;
        boolean empty = true;
        for (Slot remaining : slots) empty &= remaining == null;
        if (empty) {
            active.remove(view);
            attachments.remove(view);
            view.removeOnAttachStateChangeListener(lifecycle);
        }
        // Tint belongs to the View even if its Drawable was replaced. Exact list identity avoids
        // restoring over an independently written tint, even when it has the same colour.
        if (slot.shown == slot.nativeTint || tint(view, channel) != slot.shown) return false;
        write(view, channel, slot.nativeTint);
        return true;
    }

    private static void write(View view, int channel, ColorStateList tint) {
        if (channel == ACTION) ((ImageView) view).setImageTintList(tint);
        else NotificationProgressTone.set((ProgressBar) view, channel, tint);
    }

    private static ColorStateList tint(View view, int channel) {
        return channel == ACTION ? ((ImageView) view).getImageTintList()
                : NotificationProgressTone.get((ProgressBar) view, channel);
    }

    private static Drawable drawable(View view, int channel) {
        if (channel == ACTION) return ((ImageView) view).getDrawable();
        ProgressBar bar = (ProgressBar) view;
        return channel == NotificationProgressTone.INDETERMINATE ? bar.getIndeterminateDrawable()
                : bar.getProgressDrawable();
    }

    private static BlendMode mode(View view, int channel) {
        if (channel == ACTION) return ((ImageView) view).getImageTintBlendMode();
        ProgressBar bar = (ProgressBar) view;
        switch (channel) {
            case NotificationProgressTone.PROGRESS: return bar.getProgressTintBlendMode();
            case NotificationProgressTone.BACKGROUND: return bar.getProgressBackgroundTintBlendMode();
            case NotificationProgressTone.SECONDARY: return bar.getSecondaryProgressTintBlendMode();
            default: return bar.getIndeterminateTintBlendMode();
        }
    }

    private static boolean supported(View view, int channel, Drawable drawable, BlendMode mode) {
        if (mode != BlendMode.SRC_IN) return false;
        if (channel == ACTION) {
            return drawable instanceof VectorDrawable && ((ImageView) view).getColorFilter() == null
                    && drawable.getColorFilter() == null && !drawable.isStateful();
        }
        return isProgressShape(drawable, 0);
    }

    /** Do not tint bitmap, gradient, animated or unfamiliar progress art. Bound the inspection. */
    private static boolean isProgressShape(Drawable drawable, int depth) {
        if (drawable == null || depth > 4 || drawable.getColorFilter() != null || drawable.isStateful()) return false;
        if (drawable.getClass() == ColorDrawable.class)
            return neutral(((ColorDrawable) drawable).getColor());
        if (drawable.getClass() == GradientDrawable.class) {
            GradientDrawable shape = (GradientDrawable) drawable;
            ColorStateList color = shape.getColor();
            return shape.getColors() == null && color != null && !color.isStateful() && neutral(color.getDefaultColor());
        }
        if (drawable.getClass() == ClipDrawable.class)
            return isProgressShape(((ClipDrawable) drawable).getDrawable(), depth + 1);
        if (drawable.getClass() == InsetDrawable.class)
            return isProgressShape(((InsetDrawable) drawable).getDrawable(), depth + 1);
        if (drawable.getClass() == ScaleDrawable.class)
            return isProgressShape(((ScaleDrawable) drawable).getDrawable(), depth + 1);
        if (drawable.getClass() != LayerDrawable.class) return false;
        LayerDrawable layers = (LayerDrawable) drawable;
        if (layers.getNumberOfLayers() > 3) return false;
        for (int index = 0; index < layers.getNumberOfLayers(); index++) {
            int id = layers.getId(index);
            if (id != android.R.id.progress && id != android.R.id.secondaryProgress && id != android.R.id.background)
                return false;
            if (!isProgressShape(layers.getDrawable(index), depth + 1)) return false;
        }
        return layers.getNumberOfLayers() > 0;
    }

    static Integer target(ColorStateList tint, boolean lightText) {
        if (tint == null || tint.isStateful()) return null;
        int color = tint.getDefaultColor();
        if (color >>> 24 == 0 || !neutral(color)) return null;
        // Leave midtones, such as intentional gray accents, in the publisher's palette.
        int luma = (color >> 16) & 255;
        return (lightText ? luma >= 166 : luma <= 89) ? Foreground.mirror(color) : null;
    }

    private static boolean neutral(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 8;
    }

    private static String tintReason(ColorStateList tint) {
        if (tint == null) return "no-explicit-tint";
        if (tint.isStateful()) return "stateful-tint";
        return "native-tone-or-accent";
    }

    private static final int TRACE_BUDGET = 120;
    private static final java.util.Set<String> reported = new java.util.HashSet<>();
    private static long traceWindow;

    private static void report(View view, int channel, boolean lightText, String reason) {
        if (!Probe.trace || !view.isShown()) return;
        long now = SystemClock.uptimeMillis();
        if (now - traceWindow >= 5000L) {
            traceWindow = now;
            reported.clear();
        }
        if (reported.size() >= TRACE_BUDGET) return;
        String entry = diagnostic(view, channel, lightText, reason);
        if (reported.add(entry)) Probe.log("REMOTE_VIEWS_TONE", entry);
    }

    static String diagnostic(View view, int channel, boolean lightText, String reason) {
        Drawable paint = channel >= 0 ? drawable(view, channel)
                : view instanceof ImageView ? ((ImageView) view).getDrawable()
                : view instanceof ProgressBar ? ((ProgressBar) view).getProgressDrawable()
                : view.getBackground();
        View owner = host(view);
        StringBuilder ancestors = new StringBuilder();
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            View node = (View) parent;
            if (ancestors.length() > 640) break;
            if (ancestors.length() > 0) ancestors.append('/');
            ancestors.append(describe(node));
            if (node == owner) break;
        }
        return "preserved=" + reason + " view=" + describe(view)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " drawable=" + (paint == null ? "none" : paint.getClass().getSimpleName())
                + " channel=" + channel + " material=" + (lightText ? "light" : "dark")
                + " glassPath=" + Foreground.customSurface(view)
                + " host=" + (owner == null ? "none" : describe(owner)) + " ancestors=" + ancestors;
    }

    private static String describe(View view) {
        int id = view.getId();
        String resource = Integer.toHexString(id);
        if (id != View.NO_ID) {
            try { resource = view.getResources().getResourceEntryName(id); }
            catch (android.content.res.Resources.NotFoundException ignored) { }
        }
        return view.getClass().getSimpleName() + "#" + resource + "@"
                + Integer.toHexString(System.identityHashCode(view));
    }
}

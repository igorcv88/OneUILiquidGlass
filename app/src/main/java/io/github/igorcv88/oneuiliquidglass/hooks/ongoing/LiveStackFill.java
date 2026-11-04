package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Suppresses only the known decorative fills on DZIF's stack_pip_layout.
 *
 * This FrameLayout clips all of its children to its RippleDrawable outline. Replacing a ripple
 * layer with DrawableWrapper (even one delegating getOutline) can change its bounds/outline while
 * Samsung animates between cards and clip the ENTIRE notification. Keep the native layer instances,
 * bounds, outline, mask and ripple state intact; change only their drawing alpha.
 */
final class LiveStackFill {
    private WeakReference<View> owner = new WeakReference<>(null);
    private WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
    private final ArrayList<MutedLayer> muted = new ArrayList<>();

    private static final class MutedLayer {
        final int index;
        final Drawable drawable;
        int nativeAlpha;
        MutedLayer(int index, Drawable drawable) {
            this.index = index;
            this.drawable = drawable;
            this.nativeAlpha = drawable.getAlpha();
        }
    }

    // Samsung may reassert alpha or replace the background in an animation without calling the
    // card's bind/writer again. Check at pre-draw, not by modifying the frame or its children.
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        View frame = owner.get();
        if (frame != null) sync(frame);
        return true;
    };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { restore(); }
    };

    void sync(View frame) {
        if (frame == null || !(frame.getBackground() instanceof RippleDrawable)) {
            restore();
            return;
        }
        RippleDrawable r = (RippleDrawable) frame.getBackground();
        if (ripple.get() != r) {
            restore();
            // A stock ripple may share its ConstantState with another card. Isolate the instance
            // before setting alpha on its layers; do not replace any layer object.
            if (r.mutate() != r) return;
            owner = new WeakReference<>(frame);
            ripple = new WeakReference<>(r);
            frame.addOnAttachStateChangeListener(attach);
            ViewTreeObserver observer = frame.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(preDraw);
        }

        boolean changed = false;
        // If Samsung replaced a layer inside the SAME ripple, restore the detached native layer.
        for (int i = muted.size() - 1; i >= 0; i--) {
            MutedLayer m = muted.get(i);
            if (m.index >= r.getNumberOfLayers() || r.getDrawable(m.index) != m.drawable
                    || r.getId(m.index) == android.R.id.mask || !decorative(m.drawable)) {
                m.drawable.setAlpha(m.nativeAlpha);
                muted.remove(i);
                changed = true;
            }
        }
        for (int i = 0; i < r.getNumberOfLayers(); i++) {
            if (r.getId(i) == android.R.id.mask) continue;
            Drawable drawable = r.getDrawable(i);
            if (!decorative(drawable)) continue;
            MutedLayer lease = null;
            for (MutedLayer m : muted) if (m.index == i && m.drawable == drawable) {
                lease = m;
                break;
            }
            if (lease == null) {
                lease = new MutedLayer(i, drawable);
                muted.add(lease);
            }
            int alpha = drawable.getAlpha();
            if (alpha != 0) {
                // Keep the latest Samsung-written alpha for exact restoration on fallback.
                lease.nativeAlpha = alpha;
                drawable.setAlpha(0);
                changed = true;
            }
        }
        if (changed) frame.invalidateOutline();
    }

    private static boolean decorative(Drawable layer) {
        if (layer == null || NativeShape.interactive(layer)) return false;
        Drawable unwrapped = NativeShape.unwrap(layer);
        if (!(unwrapped instanceof GradientDrawable)) return false;
        ColorStateList color = ((GradientDrawable) unwrapped).getColor();
        if (color == null) return false;
        // Values measured in the DZIF stack_pip_layout. Unknown/native accent fills stay native.
        int value = color.getDefaultColor();
        return value == 0xffffffff || value == 0xfa3a3a3d;
    }

    void restore() {
        View frame = owner.get();
        if (frame != null) {
            frame.removeOnAttachStateChangeListener(attach);
            ViewTreeObserver observer = frame.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        }
        for (MutedLayer m : muted) m.drawable.setAlpha(m.nativeAlpha);
        muted.clear();
        ripple.clear();
        owner.clear();
        if (frame != null) frame.invalidateOutline();
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Decorative ripple fill of DZIF's stack_pip_layout.
 *
 * stack_pip_layout clips the entire notification to its background outline. Neither wrapping
 * the first ripple layer with an outline of alpha zero (#132), nor setAlpha(0) (#134 initial
 * revision), is safe: GradientDrawable.getOutline() derives alpha from its paint/own alpha.
 * A transparent drawing ColorFilter preserves the native drawable's geometry and outline.
 */
final class LiveStackFill {
    private static final ColorFilter CLEAR_PAINT =
            new PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN);

    private WeakReference<View> owner = new WeakReference<>(null);
    private WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
    private final ArrayList<MutedLayer> muted = new ArrayList<>();

    private static final class MutedLayer {
        final int index;
        final Drawable drawable;
        ColorFilter nativeFilter;
        MutedLayer(int index, Drawable drawable) {
            this.index = index;
            this.drawable = drawable;
            nativeFilter = drawable.getColorFilter();
        }
        void restore() {
            // Do not overwrite a legitimate Samsung filter installed since the last sync.
            if (drawable.getColorFilter() == CLEAR_PAINT) drawable.setColorFilter(nativeFilter);
        }
    }

    // Samsung rebinds ripple layers and filters during swipes without necessarily invoking
    // changeCardBg/updateItemBg. Reassert the drawing filter before rendering each frame.
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
            // ConstantState can be shared by siblings; mutations must remain local to this card.
            if (r.mutate() != r) return;
            owner = new WeakReference<>(frame);
            ripple = new WeakReference<>(r);
            frame.addOnAttachStateChangeListener(attach);
            ViewTreeObserver observer = frame.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(preDraw);
        }
        for (int i = muted.size() - 1; i >= 0; i--) {
            MutedLayer m = muted.get(i);
            if (m.index >= r.getNumberOfLayers() || r.getDrawable(m.index) != m.drawable
                    || r.getId(m.index) == android.R.id.mask || !decorative(m.drawable)) {
                m.restore();
                muted.remove(i);
            }
        }
        for (int i = 0; i < r.getNumberOfLayers(); i++) {
            if (r.getId(i) == android.R.id.mask) continue;
            Drawable d = r.getDrawable(i);
            if (!decorative(d)) continue;
            MutedLayer lease = null;
            for (MutedLayer m : muted) {
                if (m.index == i && m.drawable == d) { lease = m; break; }
            }
            if (lease == null) {
                lease = new MutedLayer(i, d);
                muted.add(lease);
            }
            ColorFilter current = d.getColorFilter();
            if (current != CLEAR_PAINT) {
                // Retain the latest native filter so disabling glass restores the current state.
                lease.nativeFilter = current;
                d.setColorFilter(CLEAR_PAINT);
            }
        }
    }

    private static boolean decorative(Drawable layer) {
        if (layer == null || NativeShape.interactive(layer)) return false;
        Drawable unwrapped = NativeShape.unwrap(layer);
        if (!(unwrapped instanceof GradientDrawable)) return false;
        ColorStateList color = ((GradientDrawable) unwrapped).getColor();
        if (color == null) return false;
        int value = color.getDefaultColor();
        // Observed in the DZIF stack ripple. Unknown accents and masks remain native.
        return value == 0xffffffff || value == 0xfa3a3a3d;
    }

    void restore() {
        View frame = owner.get();
        if (frame != null) {
            frame.removeOnAttachStateChangeListener(attach);
            ViewTreeObserver observer = frame.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(preDraw);
        }
        for (MutedLayer m : muted) m.restore();
        muted.clear();
        ripple.clear();
        owner.clear();
    }
}

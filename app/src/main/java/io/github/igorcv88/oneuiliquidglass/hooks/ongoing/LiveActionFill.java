package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import java.lang.ref.WeakReference;

/**
 * The expanded ongoing card already owns a glass surface. Its native action buttons may still
 * draw an independent white capsule inside that surface. Suppress only the opaque content layer
 * of their RippleDrawable, leaving the ripple, bounds, outline, masks and click listener native.
 * The wrapper reports the content's original opaque geometry so RippleDrawable does not try to
 * build an implicit mask from a deliberately empty draw() call.
 */
final class LiveActionFill {
    private WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
    private WeakReference<Drawable> original = new WeakReference<>(null);
    private WeakReference<HiddenContent> hidden = new WeakReference<>(null);

    void sync(View button, boolean glass) {
        if (!glass || button == null) { restore(); return; }
        Drawable bg = button.getBackground();
        if (!(bg instanceof RippleDrawable)) { restore(); return; }
        RippleDrawable current = (RippleDrawable) bg;
        if (ripple.get() == current && hidden.get() != null && current.getNumberOfLayers() > 0
                && current.getDrawable(0) == hidden.get()) return;
        restore();
        if (current.getNumberOfLayers() == 0) return;
        Drawable content = current.getDrawable(0);
        Drawable shape = NativeShape.unwrap(content);
        if (!(shape instanceof GradientDrawable) || shape.getAlpha() != 255) return;
        ColorStateList color = ((GradientDrawable) shape).getColor();
        // DZIF action0 / ongoing_action_text_button: a plain solid white opaque content.
        // Unknown, translucent, patterned or coloured button materials stay native.
        if (color == null || color.getDefaultColor() != 0xffffffff) return;
        // Inflated buttons can share RippleDrawable.ConstantState. Isolate this instance before
        // replacing a layer, otherwise a sibling button can inherit our hidden content.
        if (current.mutate() != current) return;
        content = current.getDrawable(0);
        HiddenContent replacement = new HiddenContent(content);
        current.setDrawable(0, replacement);
        original = new WeakReference<>(content);
        hidden = new WeakReference<>(replacement);
        ripple = new WeakReference<>(current);
    }

    void restore() {
        RippleDrawable r = ripple.get();
        if (r != null && hidden.get() != null && original.get() != null && r.getNumberOfLayers() > 0
                && r.getDrawable(0) == hidden.get()) r.setDrawable(0, original.get());
        original.clear();
        hidden.clear();
        ripple.clear();
    }

    boolean active() { return hidden.get() != null && ripple.get() != null; }

    private static final class HiddenContent extends DrawableWrapper {
        HiddenContent(Drawable nativeContent) { super(nativeContent); }
        @Override public void draw(Canvas canvas) { }
        // AOSP RippleDrawable uses the content layer's opacity to select its implicit mask.
        // Keep the native outline and use its normal opaque-content clipping path.
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        @Override public Drawable mutate() { return this; }
        @Override public ConstantState getConstantState() { return null; }
    }
}

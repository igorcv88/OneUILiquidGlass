package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Content-only lease for the classified stack's decorative ripple frame. */
final class LiveStackFill {
    private WeakReference<View> owner = new WeakReference<>(null);
    private WeakReference<RippleDrawable> ripple = new WeakReference<>(null);
    private final ArrayList<Hidden> layers = new ArrayList<>();

    void sync(View frame) {
        Drawable background = frame.getBackground();
        if (!(background instanceof RippleDrawable)) { restore(); return; }
        RippleDrawable r = (RippleDrawable) background;
        if (ripple.get() != r) { restore(); r.mutate(); ripple = new WeakReference<>(r); owner = new WeakReference<>(frame); }
        boolean changed = false;
        for (int i = 0; i < r.getNumberOfLayers(); i++) {
            if (r.getId(i) == android.R.id.mask) continue;
            Drawable d = r.getDrawable(i);
            if (d instanceof Hidden) continue;
            Drawable shape = NativeShape.unwrap(d);
            if (!(shape instanceof GradientDrawable) || ((GradientDrawable) shape).getColor() == null) continue;
            Hidden hidden = new Hidden(d);
            r.setDrawable(i, hidden);
            layers.add(hidden);
            changed = true;
        }
        if (changed) { r.invalidateSelf(); frame.invalidateOutline(); }
    }

    void restore() {
        RippleDrawable r = ripple.get();
        if (r != null) for (int i = 0; i < r.getNumberOfLayers(); i++) {
            Drawable d = r.getDrawable(i);
            if (layers.contains(d)) r.setDrawable(i, ((Hidden) d).getDrawable());
        }
        layers.clear();
        ripple.clear();
        View frame = owner.get();
        if (frame != null && frame.getBackground() == r) frame.invalidateOutline();
        owner.clear();
    }

    private static final class Hidden extends DrawableWrapper {
        Hidden(Drawable d) { super(d); }
        @Override public void draw(Canvas canvas) { }
        @Override public void getOutline(Outline outline) { super.getOutline(outline); outline.setAlpha(0f); }
        @Override public ConstantState getConstantState() { return null; }
        @Override public Drawable mutate() { return this; }
    }
}

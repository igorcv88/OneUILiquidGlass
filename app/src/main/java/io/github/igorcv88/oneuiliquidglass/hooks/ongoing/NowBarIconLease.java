package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/** The eligible Now Bar's native AdaptiveIconDrawable already contains a monochrome glyph. */
final class NowBarIconLease {
    private WeakReference<ImageView> image = new WeakReference<>(null);
    // The ImageView stops owning the native drawable as soon as the monochrome glyph replaces it.
    // Keep it strongly for the active lease; only views/card references remain weak.
    private Drawable original;
    private WeakReference<Drawable> applied = new WeakReference<>(null);
    private WeakReference<View> disc = new WeakReference<>(null);
    private ColorStateList nativeDisc, nativeTint;
    private long generation, nextSample;
    private Boolean lightBackdrop;
    private int ink = Color.WHITE;

    private void contrast(ImageView target) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!target.isAttachedToWindow() || !target.isHardwareAccelerated() || !target.isShown()
                || target.getWidth() == 0 || now < nextSample) return;
        nextSample = now + 1000;
        long epoch = generation;
        io.github.igorcv88.oneuiliquidglass.glass.ShadeTone.measure(target, luma -> {
            ImageView current = image.get();
            if (epoch != generation || current != target || !Double.isFinite(luma)
                    || !current.isAttachedToWindow() || current.getDrawable() != applied.get()) return;
            boolean light = io.github.igorcv88.oneuiliquidglass.glass.ShadeTone.lightBackdrop((float) luma, lightBackdrop);
            applyTone(light);
        });
    }

    void applyTone(boolean light) {
        lightBackdrop = light;
        int color = light ? Color.BLACK : Color.WHITE;
        Drawable glyph = applied.get();
        if (glyph != null && color != ink) {
            ink = color;
            glyph.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            ImageView target = image.get();
            if (target != null) target.invalidate();
        }
    }

    ImageView target() { return image.get(); }

    void sync(ImageView target, boolean glass) {
        if (!glass || target == null) { restore(); return; }
        if (image.get() == target && target.getDrawable() == applied.get() && applied.get() != null) {
            if (target.getImageTintList() != null) {
                nativeTint = target.getImageTintList();
                target.setImageTintList(null);
            }
            transparentDisc(target);
            contrast(target);
            return;
        }
        restore();
        Drawable nativeIcon = target.getDrawable();
        if (!(nativeIcon instanceof AdaptiveIconDrawable)) return;
        Drawable glyph = ((AdaptiveIconDrawable) nativeIcon).getMonochrome();
        if (glyph == null || glyph.getConstantState() == null) return;
        Drawable mono = glyph.getConstantState().newDrawable(target.getResources()).mutate();
        mono.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        image = new WeakReference<>(target);
        original = nativeIcon;
        applied = new WeakReference<>(mono);
        nativeTint = target.getImageTintList();
        target.setImageTintList(null);
        target.setImageDrawable(mono);
        transparentDisc(target);
        contrast(target);
    }

    private void transparentDisc(ImageView target) {
        Object parent = target.getParent();
        if (!(parent instanceof View)) return;
        View container = (View) parent;
        View saved = disc.get();
        if (saved != container && !NativeShape.cardLike(container)) return;
        if (saved != null && saved != container) restoreDisc();
        Object color = NativeShape.call(container, "getCardBackgroundColor");
        if (!(color instanceof ColorStateList)) return;
        if (((ColorStateList) color).getDefaultColor() == Color.TRANSPARENT) return;
        nativeDisc = (ColorStateList) color;
        disc = new WeakReference<>(container);
        cardColor(container, ColorStateList.valueOf(Color.TRANSPARENT));
    }

    void restore() {
        generation++;
        nextSample = 0;
        lightBackdrop = null;
        ink = Color.WHITE;
        ImageView target = image.get();
        if (target != null && target.getDrawable() == applied.get() && original != null) {
            target.setImageDrawable(original);
        }
        // Tint belongs to the ImageView even if Samsung replaced its drawable.
        if (target != null && target.getImageTintList() == null) target.setImageTintList(nativeTint);
        nativeTint = null;
        restoreDisc();
        image.clear();
        original = null;
        applied.clear();
    }

    private void restoreDisc() {
        View container = disc.get();
        if (container != null && nativeDisc != null) {
            Object current = NativeShape.call(container, "getCardBackgroundColor");
            if (current instanceof ColorStateList && ((ColorStateList) current).getDefaultColor() == Color.TRANSPARENT)
                cardColor(container, nativeDisc);
        }
        disc.clear();
        nativeDisc = null;
    }

    private static void cardColor(View card, ColorStateList color) {
        try { card.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(card, color); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}

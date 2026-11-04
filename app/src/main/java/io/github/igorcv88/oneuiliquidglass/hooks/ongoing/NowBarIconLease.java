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
    private WeakReference<Drawable> original = new WeakReference<>(null);
    private WeakReference<Drawable> applied = new WeakReference<>(null);
    private WeakReference<View> disc = new WeakReference<>(null);
    private ColorStateList nativeDisc;

    ImageView target() { return image.get(); }

    void sync(ImageView target, boolean glass) {
        if (!glass || target == null) { restore(); return; }
        if (image.get() == target && target.getDrawable() == applied.get() && applied.get() != null) {
            transparentDisc(target);
            return;
        }
        restore();
        Drawable nativeIcon = target.getDrawable();
        if (!(nativeIcon instanceof AdaptiveIconDrawable) || target.getImageTintList() != null) return;
        Drawable glyph = ((AdaptiveIconDrawable) nativeIcon).getMonochrome();
        if (glyph == null || glyph.getConstantState() == null) return;
        Drawable mono = glyph.getConstantState().newDrawable(target.getResources()).mutate();
        mono.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        image = new WeakReference<>(target);
        original = new WeakReference<>(nativeIcon);
        applied = new WeakReference<>(mono);
        target.setImageDrawable(mono);
        transparentDisc(target);
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
        ImageView target = image.get();
        if (target != null && target.getDrawable() == applied.get() && original.get() != null)
            target.setImageDrawable(original.get());
        restoreDisc();
        image.clear();
        original.clear();
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

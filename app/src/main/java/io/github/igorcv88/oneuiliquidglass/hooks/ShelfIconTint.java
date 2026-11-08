package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** One lease for the shelf glyph's view and drawable filters. No colour classification here. */
final class ShelfIconTint {
    private static final Set<ImageView> owners = Collections.newSetFromMap(new WeakHashMap<>());
    private ColorFilter originalView, originalDrawable, applied;
    private WeakReference<Drawable> drawable = new WeakReference<>(null);
    private int ink;

    static boolean owns(ImageView view) { return owners.contains(view); }

    boolean current(ImageView view, int color) {
        Drawable d = view.getDrawable();
        return applied != null && ink == color && view.getColorFilter() == applied
                && d == drawable.get() && (d == null || d.getColorFilter() == applied);
    }

    boolean apply(ImageView view, int color) {
        if (current(view, color)) return false;
        if (owners.add(view)) Foreground.releaseIconTone(view);
        Drawable d = view.getDrawable(), previous = drawable.get();
        if (previous != d) {
            if (previous != null && previous.getColorFilter() == applied)
                previous.setColorFilter(originalDrawable);
            originalDrawable = d == null ? null
                    : d.getColorFilter() == applied ? originalView : d.getColorFilter();
            drawable = new WeakReference<>(d);
        } else if (d != null && d.getColorFilter() != applied) {
            originalDrawable = d.getColorFilter();
        }
        if (view.getColorFilter() != applied) originalView = view.getColorFilter();
        if (applied == null || ink != color) {
            ink = color;
            applied = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        if (view.getColorFilter() != applied) view.setColorFilter(applied);
        // ImageView caches its filter. Calling setColorFilter with the same object may be a no-op
        // after Samsung tinted/rebound the drawable directly: repair the actual glyph as well.
        if (d != null && d.getColorFilter() != applied) d.setColorFilter(applied);
        view.invalidate();
        return true;
    }

    void restore(ImageView view) {
        if (applied == null) return;
        Drawable d = drawable.get();
        ColorFilter current = d == null ? null : d.getColorFilter();
        ColorFilter restored = current == applied ? originalDrawable : current;
        // Restoring the view writes through to its current drawable; preserve a newer OEM filter.
        boolean restoredView = view != null && view.getColorFilter() == applied;
        if (restoredView) view.setColorFilter(originalView);
        if (d != null && (current == applied || restoredView && view.getDrawable() == d)
                && d.getColorFilter() != restored) d.setColorFilter(restored);
        if (view != null) owners.remove(view);
        applied = null;
        originalView = null;
        originalDrawable = null;
        drawable.clear();
    }
}

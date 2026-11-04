package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;

/** Suspends only the competing region, without replacing a CardView's background or SemBlurInfo. */
final class NativeRegionLease {
    private static final java.util.WeakHashMap<Drawable, Entry> ENTRIES = new java.util.WeakHashMap<>();
    private static final class Entry {
        int alpha, owners;
        Entry(int alpha) { this.alpha = alpha; }
    }
    private WeakReference<Drawable> saved = new WeakReference<>(null);

    void sync(View layer, boolean ownRegion) {
        Drawable current = layer == null ? null : layer.getBackground();
        Drawable old = saved.get();
        if (!ownRegion || current != old) restore();
        if (!ownRegion || current == null || !current.getClass().getName().contains("BlurDrawable")) return;
        if (saved.get() != current) {
            saved = new WeakReference<>(current);
            Entry entry = ENTRIES.get(current);
            if (entry == null) { entry = new Entry(current.getAlpha()); ENTRIES.put(current, entry); }
            entry.owners++;
        }
        Entry entry = ENTRIES.get(current);
        if (current.getAlpha() != 0) {
            // A Samsung fade/recolour writer can update the same drawable in place.
            entry.alpha = current.getAlpha();
        }
        if (current.getAlpha() != 0) {
            current.setAlpha(0);
            current.invalidateSelf();
            layer.invalidate();
        }
    }

    void restore() {
        Drawable d = saved.get();
        Entry entry = d == null ? null : ENTRIES.get(d);
        if (entry != null && --entry.owners == 0) {
            ENTRIES.remove(d);
            if (d.getAlpha() == 0) {
                d.setAlpha(entry.alpha);
                d.invalidateSelf();
            }
        }
        saved.clear();
    }
}

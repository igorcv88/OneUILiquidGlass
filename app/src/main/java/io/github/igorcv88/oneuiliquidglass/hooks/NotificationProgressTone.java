package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewParent;
import android.widget.ProgressBar;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Tone of the framework notification template's progress bar ({@code android:id/progress}, a
 * {@link ProgressBar} inside an ExpandableNotificationRow), following the same rule as the
 * template text in {@link Foreground}: on a card whose material swapped, a tint list of the
 * theme's text tone (near-neutral grey) is mirrored (255 - value, alpha kept); accent tints, a
 * missing tint and unknown layouts are left alone.
 *
 * <p>Only the four public tint lists are written (progress, progress background, secondary
 * progress, indeterminate). The drawables, their own per-layer alpha (the track's disabledAlpha),
 * the tint modes, geometry, progress/max values and the indeterminate animation stay as the
 * publisher built them. Each channel is leased separately with the publisher's list, which is
 * put back when the card stops being swapped or the bar leaves the template. A tint written by
 * a RemoteViews reapply is detected by {@link #drift} and becomes the new original; an old lease
 * never restores over it.</p>
 *
 * <p>Retention: a lease holds only ColorStateLists (no View, Drawable or callback), keyed weakly
 * by the bar. Main thread only.</p>
 */
final class NotificationProgressTone {
    private NotificationProgressTone() {}

    static final int PROGRESS = 0, BACKGROUND = 1, SECONDARY = 2, INDETERMINATE = 3, CHANNELS = 4;
    private static final String[] NAMES = { "progress", "background", "secondary", "indeterminate" };

    private static final class Slot {
        /** The publisher's list, given back on restore (may be stateful). */
        final ColorStateList original;
        /** The non-stateful mirrored list this class set. */
        final ColorStateList applied;
        Slot(ColorStateList original, ColorStateList applied) { this.original = original; this.applied = applied; }
    }
    private static final class Lease { final Slot[] slots = new Slot[CHANNELS]; }

    private static final WeakHashMap<ProgressBar, Lease> active = new WeakHashMap<>();
    private static final Set<String> reported = new HashSet<>();

    static boolean tracking() { return !active.isEmpty(); }
    static boolean owns(View v) { return v instanceof ProgressBar && active.containsKey(v); }

    /** The framework template's bar: id android:id/progress. Entry names alone never qualify. */
    static boolean templateId(String pkg, String entry) {
        return "android".equals(pkg) && "progress".equals(entry);
    }

    /**
     * The colour to show instead of {@code color} on a swapped card, or null to leave it: only the
     * theme's text tone is mirrored, and a fully transparent tint is not touched.
     */
    static Integer target(int color, boolean lightText) {
        if ((color >>> 24) == 0 || !Foreground.themeTone(color, lightText)) return null;
        return Foreground.mirror(color);
    }

    static boolean template(View view) {
        int id = view.getId();
        if (id != android.R.id.progress) return false;
        try {
            android.content.res.Resources r = view.getResources();
            return templateId(r.getResourcePackageName(id), r.getResourceEntryName(id));
        } catch (RuntimeException e) { return false; }
    }

    private static boolean candidate(ProgressBar bar) {
        if (!template(bar)) return false;
        ViewParent p = bar.getParent();
        for (int depth = 0; p instanceof View && depth < 24; depth++) {
            if (p.getClass().getName().endsWith("ExpandableNotificationRow")) return true;
            p = p.getParent();
        }
        return false;
    }

    static ColorStateList get(ProgressBar b, int channel) {
        switch (channel) {
            case PROGRESS: return b.getProgressTintList();
            case BACKGROUND: return b.getProgressBackgroundTintList();
            case SECONDARY: return b.getSecondaryProgressTintList();
            default: return b.getIndeterminateTintList();
        }
    }
    static void set(ProgressBar b, int channel, ColorStateList list) {
        switch (channel) {
            case PROGRESS: b.setProgressTintList(list); break;
            case BACKGROUND: b.setProgressBackgroundTintList(list); break;
            case SECONDARY: b.setSecondaryProgressTintList(list); break;
            default: b.setIndeterminateTintList(list); break;
        }
    }
    /** Ours if it is the very list set, or an equal one-colour list (a framework copy). */
    static boolean same(ColorStateList now, ColorStateList applied) {
        return now == applied || (now != null && applied != null && !now.isStateful() && !applied.isStateful()
                && now.getDefaultColor() == applied.getDefaultColor());
    }

    /** Whether a publisher/SystemUI write replaced one of the tints this class set. */
    static boolean drift(View v) {
        if (!(v instanceof ProgressBar)) return false;
        ProgressBar b = (ProgressBar) v;
        Lease l = active.get(b);
        if (l == null) return false;
        for (int c = 0; c < CHANNELS; c++)
            if (l.slots[c] != null && !same(get(b, c), l.slots[c].applied)) return true;
        return false;
    }

    /** Puts back every channel still showing our tint; a channel rewritten since is left as it is. */
    private static boolean release(ProgressBar b, Lease l) {
        if (l == null) return false;
        active.remove(b);
        boolean changed = false;
        for (int c = 0; c < CHANNELS; c++) {
            Slot s = l.slots[c];
            if (s == null || !same(get(b, c), s.applied)) continue;
            set(b, c, s.original);
            changed = true;
            report(c, "restored", s.original, b);
        }
        return changed;
    }

    /**
     * One view of a Foreground walk. swap false, a bar that is no longer the template's, or one
     * outside a row: its leased channels are restored. Returns whether a tint was written.
     */
    static boolean apply(View v, boolean swap, boolean lightText) {
        if (!(v instanceof ProgressBar)) return false;
        ProgressBar b = (ProgressBar) v;
        Lease l = active.get(b);
        if (!swap || !candidate(b)) return release(b, l);
        if (l == null) l = new Lease();
        boolean changed = false, any = false;
        int[] state = b.getDrawableState();
        for (int c = 0; c < CHANNELS; c++) {
            ColorStateList now = get(b, c);
            Slot s = l.slots[c];
            boolean ours = s != null && same(now, s.applied);
            // A list the publisher set since is the new original; ours resolves back to its source.
            ColorStateList base = ours ? s.original : now;
            if (base == null) {
                // The drawable's own colours are unknown here: never guessed, never recoloured.
                l.slots[c] = null;
                report(c, "no-tint", null, b);
                continue;
            }
            Integer to = target(base.getColorForState(state, base.getDefaultColor()), lightText);
            if (to == null) {
                if (ours) { set(b, c, base); changed = true; }
                l.slots[c] = null;
                report(c, "native-accent", base, b);
                continue;
            }
            any = true;
            if (ours && s.applied.getDefaultColor() == to) continue;
            ColorStateList applied = ColorStateList.valueOf(to);
            l.slots[c] = new Slot(base, applied);
            set(b, c, applied);
            changed = true;
            report(c, "swapped", base, b);
        }
        if (any) active.put(b, l); else active.remove(b);
        return changed;
    }

    private static void report(int channel, String action, ColorStateList list, ProgressBar b) {
        if (!Probe.trace || reported.size() >= 64) return;
        String color = list == null ? "null" : Integer.toHexString(list.getDefaultColor()) + (list.isStateful() ? "*" : "");
        String key = channel + ":" + action + ":" + color + ":" + b.isIndeterminate();
        if (reported.add(key)) Probe.log("PROGRESS_TONE", "channel=" + NAMES[channel] + " action=" + action
                + " native=" + color + " indeterminate=" + b.isIndeterminate()
                + " drawable=" + (b.getProgressDrawable() == null ? "null" : b.getProgressDrawable().getClass().getSimpleName())
                + " class=" + b.getClass().getName());
    }
}


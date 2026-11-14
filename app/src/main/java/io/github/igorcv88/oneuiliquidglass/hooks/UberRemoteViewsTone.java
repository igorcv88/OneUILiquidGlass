package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Reversible tonal treatment for verified Uber RemoteViews in the notification shade.
 * The published progress widget is two solid FrameLayout backgrounds and two ImageViews,
 * not a system ProgressBar. Never recolour its photo/vehicle artwork or unknown drawables.
 *
 * The selection is by the app's resource package, exact view id, known parent subtree and
 * notification-row ownership. Unknown/new layouts fail closed.
 */
final class UberRemoteViewsTone {
    private UberRemoteViewsTone() {}

    enum Role { NONE, PLATE, MODEL, PROGRESS_FILLED, PROGRESS_REMAINING, PROGRESS_INDICATOR, PROGRESS_END }
    private static final String UBER = "com.ubercab";
    private static final String ACCESSORY = "ub__ooa_layout_rider_trip_expanded_accessory_container";
    private static final String PROGRESS = "ub__ooa_layout_rider_trip_expanded_progress_container";
    private static final WeakHashMap<View, Lease> active = new WeakHashMap<>();
    private static final Set<String> reported = new HashSet<>();

    static Role classify(String pkg, String entry) {
        if (!UBER.equals(pkg) || entry == null) return Role.NONE;
        switch (entry) {
            case "ub__ooa_element_expanded_vehicle_plate": return Role.PLATE;
            case "ub__ooa_element_expanded_vehicle_model": return Role.MODEL;
            case "progress_bar_filled": return Role.PROGRESS_FILLED;
            case "progress_bar_not_filled": return Role.PROGRESS_REMAINING;
            case "progress_bar_indicator": return Role.PROGRESS_INDICATOR;
            case "progress_bar_end_image": return Role.PROGRESS_END;
            default: return Role.NONE;
        }
    }

    private static String resource(View v, boolean pkg) {
        if (v == null || v.getId() == View.NO_ID || v.getId() == 0) return null;
        try {
            return pkg ? v.getResources().getResourcePackageName(v.getId())
                    : v.getResources().getResourceEntryName(v.getId());
        } catch (RuntimeException ignored) { return null; }
    }

    private static Role role(View v) { return classify(resource(v, true), resource(v, false)); }

    private static boolean inside(View v, String ancestorName) {
        boolean owner = false, row = false;
        ViewParent p = v.getParent();
        for (int depth = 0; p instanceof View && depth < 24; depth++) {
            View view = (View) p;
            if (view.getClass().getName().endsWith("ExpandableNotificationRow")) { row = true; break; }
            if (UBER.equals(resource(view, true)) && ancestorName.equals(resource(view, false))) owner = true;
            p = view.getParent();
        }
        return row && owner;
    }

    static boolean vehicleTextCandidate(Role role, boolean accessory, boolean row) {
        return (role == Role.PLATE || role == Role.MODEL) && accessory && row;
    }

    static boolean vehicleText(View v) {
        if (!(v instanceof TextView)) return false;
        Role kind = role(v);
        if (kind != Role.PLATE && kind != Role.MODEL) return false;
        // The screenshot + TEXT_ROW prove these two labels are rendered over the card's light
        // glass but customSurface rejects an ancestor. Do not make other app text eligible.
        return inside(v, ACCESSORY) && v.getBackground() == null && v.getForeground() == null;
    }

    private static final class Lease {
        final Role role;
        final Drawable drawable;
        final ColorStateList originalTint;
        final PorterDuff.Mode originalMode;
        final boolean image;
        ColorStateList appliedTint;
        int originalColor;
        Lease(Role role, Drawable drawable, ColorStateList tint, PorterDuff.Mode mode, boolean image, int color) {
            this.role=role; this.drawable=drawable; originalTint=tint; originalMode=mode;
            this.image=image; originalColor=color;
        }
    }

    static boolean tracking() { return !active.isEmpty(); }
    static boolean owns(View view) { return active.containsKey(view); }

    static boolean drift(View view) {
        Lease l = active.get(view);
        return l != null && (drawable(view, l.image) != l.drawable
                || !matches(tint(view, l.image), l.appliedTint));
    }

    private static Drawable drawable(View v, boolean image) {
        return image ? ((ImageView)v).getDrawable() : v.getBackground();
    }
    private static ColorStateList tint(View v, boolean image) {
        return image ? ((ImageView)v).getImageTintList() : v.getBackgroundTintList();
    }
    private static PorterDuff.Mode mode(View v, boolean image) {
        return image ? ((ImageView)v).getImageTintMode() : v.getBackgroundTintMode();
    }
    private static void setTint(View v, boolean image, ColorStateList tint, PorterDuff.Mode mode) {
        if (image) {
            ImageView i = (ImageView)v;
            i.setImageTintMode(mode);
            i.setImageTintList(tint);
        } else {
            v.setBackgroundTintMode(mode);
            v.setBackgroundTintList(tint);
        }
    }
    private static boolean matches(ColorStateList a, ColorStateList b) {
        return a == b || (a != null && b != null && !a.isStateful() && !b.isStateful()
                && a.getDefaultColor() == b.getDefaultColor());
    }
    private static boolean restore(View v, Lease l) {
        if (l == null) return false;
        active.remove(v);
        // A publisher rebind has priority over our old lease. Never restore over its own tint.
        if (!matches(tint(v, l.image), l.appliedTint)) return false;
        setTint(v, l.image, l.originalTint, l.originalMode);
        report(l.role, "restored", l.originalColor, l.drawable);
        return true;
    }

    private static Integer solid(Drawable d, boolean ourTintActive) {
        // setBackgroundTintList may install a filter on the same drawable. While our lease
        // owns that tint, still read its original solid fill rather than treating it as
        // a new publisher filter and oscillating on every shade refresh.
        if (d == null || (!ourTintActive && d.getColorFilter() != null)) return null;
        if (d instanceof ColorDrawable) return ((ColorDrawable) d).getColor();
        if (d instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) d;
            ColorStateList c = g.getColor();
            if (c != null && !c.isStateful() && g.getColors() == null) return c.getDefaultColor();
        }
        return null;
    }

    /** This entry point only receives a verified role and parent from apply(). */
    static boolean tintKnown(View v, Role role, boolean swap, boolean lightText) {
        boolean image = role == Role.PROGRESS_INDICATOR || role == Role.PROGRESS_END;
        Lease previous = active.get(v);
        if (previous != null && (previous.role != role || previous.image != image
                || drawable(v, image) != previous.drawable
                || !matches(tint(v, image), previous.appliedTint))) {
            restore(v, previous);
            previous = null;
        }
        if (!swap) return restore(v, previous);

        Drawable d = drawable(v, image);
        if (d == null) { report(role, "no-drawable", 0, null); return restore(v, previous); }
        ColorStateList originalTint = previous == null ? tint(v, image) : previous.originalTint;
        PorterDuff.Mode originalMode = previous == null ? mode(v, image) : previous.originalMode;
        Integer source = null;
        if (originalTint != null && !originalTint.isStateful()) source = originalTint.getDefaultColor();
        else if (originalTint == null) source = image
                ? (previous == null ? Foreground.lightGlyph(d) : previous.originalColor)
                : solid(d, previous != null);
        if (source == null || (source >>> 24) == 0 || !Foreground.themeTone(source, lightText)) {
            report(role, source == null ? "unsupported" : "native-accent", source == null ? 0 : source, d);
            return restore(v, previous);
        }

        int target = Foreground.mirror(source);
        if (previous != null && previous.originalColor == source
                && previous.appliedTint != null && previous.appliedTint.getDefaultColor() == target) return false;
        Lease lease = previous == null ? new Lease(role, d, originalTint, originalMode, image, source) : previous;
        lease.originalColor = source;
        lease.appliedTint = ColorStateList.valueOf(target);
        // Tint only a verified single-colour source, retaining its dimensions, alpha and shape.
        setTint(v, image, lease.appliedTint, PorterDuff.Mode.SRC_IN);
        active.put(v, lease);
        report(role, "swapped", source, d);
        return true;
    }

    static boolean apply(View v, boolean swap, boolean lightText) {
        if (!(v instanceof FrameLayout) && !(v instanceof ImageView)) return false;
        Role r = role(v);
        Lease l = active.get(v);
        if (r != Role.PROGRESS_FILLED && r != Role.PROGRESS_REMAINING
                && r != Role.PROGRESS_INDICATOR && r != Role.PROGRESS_END)
            return restore(v, l);
        boolean expected = (r == Role.PROGRESS_FILLED || r == Role.PROGRESS_REMAINING)
                ? v instanceof FrameLayout : v instanceof ImageView;
        if (!expected || !inside(v, PROGRESS)) return restore(v, l);
        return tintKnown(v, r, swap, lightText);
    }

    private static void report(Role role, String action, int color, Drawable d) {
        if (!Probe.trace || reported.size() >= 48) return;
        String drawableClass = d == null ? "null" : d.getClass().getSimpleName();
        String key = role + ":" + action + ":" + drawableClass + ":" + Integer.toHexString(color);
        if (reported.add(key)) Probe.log("UBER_RV_TONE", "role=" + role + " action=" + action
                + " native=" + Integer.toHexString(color) + " drawable=" + drawableClass);
    }
}

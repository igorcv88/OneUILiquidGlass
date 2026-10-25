package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The text and icon tone of a card that changed material style (ShadeTone.materialFor): over light
 * glass the theme's light text turns dark, over dark glass the theme's dark text turns light. Only
 * views of the standard templates change: text views whose id belongs to the framework ("android",
 * the notification templates) or to SystemUI, and the expand icon; a custom RemoteViews layout
 * carries the app's ids (or none) and is never touched. Among those, only the theme's text tone
 * (near-neutral greys: primary, secondary, time) changes; accent text, coloured spans, app icons
 * and action colours are left alone. Each grey is mirrored (255 - value, alpha kept), so the primary/secondary hierarchy holds.
 * Applied to the inflated views, never the RemoteViews; switching back restores each view's own
 * colours. A view whose content set a new colour since is taken as it is. Main thread only.
 *
 * <p>Replaces the round-36 text halo, a shadow behind every glyph that read as a dirty double
 * outline on the device (25/10).</p>
 */
final class Foreground {
    private Foreground() {}
    /** Views whose tone this class changed: what they had, and what they were given. */
    private static final WeakHashMap<Object, Rec> swapped = new WeakHashMap<>();
    static final class Rec {
        Object saved;
        int applied;
        Rec(Object saved) { this.saved = saved; }
    }
    /** One view's tone; a seam so the bookkeeping runs without Android in tests. */
    interface Ink {
        Object key();
        /** The colour drawn now (text colour, or the default colour of an icon tint). */
        int color();
        void set(int color);
        Object save();
        void restore(Object saved);
    }
    private static final class Text implements Ink {
        final TextView t;
        Text(TextView t) { this.t = t; }
        @Override public Object key() { return t; }
        @Override public int color() { return t.getCurrentTextColor(); }
        @Override public void set(int color) { t.setTextColor(color); }
        @Override public Object save() { return t.getTextColors(); }
        @Override public void restore(Object saved) { if (saved instanceof ColorStateList) t.setTextColor((ColorStateList) saved); }
    }
    /** Only icons tinted through imageTintList: the expand chevron and similar glyphs, never app icons. */
    private static final class Icon implements Ink {
        final ImageView i;
        Icon(ImageView i) { this.i = i; }
        @Override public Object key() { return i; }
        @Override public int color() { return i.getImageTintList().getDefaultColor(); }
        @Override public void set(int color) { i.setImageTintList(ColorStateList.valueOf(color)); }
        @Override public Object save() { return i.getImageTintList(); }
        @Override public void restore(Object saved) { i.setImageTintList((ColorStateList) saved); }
    }

    /** swap false restores every view under {@code root}. Returns the views changed. */
    static int apply(View root, boolean swap, boolean lightText) {
        return root == null ? 0 : walk(root, swap, lightText);
    }
    private static int walk(View v, boolean swap, boolean lightText) {
        int changed = 0;
        if (v instanceof TextView) {
            if (standard(v, false)) changed += ink(new Text((TextView) v), swap, lightText, swapped) ? 1 : 0;
        } else if (v instanceof ImageView && (((ImageView) v).getImageTintList() != null || swapped.containsKey(v))) {
            ImageView i = (ImageView) v;
            if (i.getImageTintList() == null) swapped.remove(v);
            else if (standard(v, true)) changed += ink(new Icon(i), swap, lightText, swapped) ? 1 : 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                // A group's child rows are cards of their own, with their own material.
                if (c.getClass().getName().endsWith("ExpandableNotificationRow")) continue;
                changed += walk(c, swap, lightText);
            }
        }
        return changed;
    }
    /**
     * One view. swap: a view of the theme's tone gets the mirrored tone; one already swapped is
     * left, unless its content set a colour since (then that colour is the new original, swapped
     * again if it is of the theme's tone, released otherwise). !swap: a view still showing the tone
     * it was given gets its own colours back.
     */
    static boolean ink(Ink ink, boolean swap, boolean lightText, Map<Object, Rec> swapped) {
        Rec r = swapped.get(ink.key());
        int now = ink.color();
        if (!swap) {
            if (r == null) return false;
            swapped.remove(ink.key());
            if (now != r.applied) return false;
            ink.restore(r.saved);
            return true;
        }
        if (r != null && now == r.applied) return false;
        if (!themeTone(now, lightText)) {
            if (r != null) swapped.remove(ink.key());
            return false;
        }
        if (r == null) { r = new Rec(ink.save()); swapped.put(ink.key(), r); }
        else r.saved = ink.save();
        r.applied = mirror(now);
        ink.set(r.applied);
        return true;
    }
    /** Whether a view is part of a standard template (see the class comment); icon: only the expand icon. */
    private static boolean standard(View v, boolean icon) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) return false;
        try {
            android.content.res.Resources r = v.getResources();
            return standardId(r.getResourcePackageName(id), v.getContext().getPackageName(), icon ? r.getResourceEntryName(id) : null);
        } catch (RuntimeException e) { return false; }
    }
    /**
     * pkg: the package of the view's id; host: SystemUI's package; entry: the id's name for an icon,
     * null for text. Framework and SystemUI ids only; an icon only when its name says expand.
     */
    static boolean standardId(String pkg, String host, String entry) {
        boolean ours = "android".equals(pkg) || (pkg != null && pkg.equals(host));
        if (!ours) return false;
        return entry == null || entry.toLowerCase(java.util.Locale.ROOT).contains("expand");
    }
    /** The grey on the other side of the scale, alpha kept: white to black, a light grey to a dark one. */
    static int mirror(int c) {
        return (c & 0xff000000) | (~c & 0x00ffffff);
    }
    /** Whether a colour is the theme's notification text tone (near white or near black, greys included). */
    static boolean themeTone(int c, boolean lightText) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > 40) return false;
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return lightText ? luma >= 0.55f : luma <= 0.45f;
    }
}

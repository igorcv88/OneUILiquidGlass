package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.WeakHashMap;

/**
 * A soft shadow behind the text of a notification whose measured veil was capped (debug.oulg.veilmax):
 * the legibility the veil no longer gives over a white app, given to the glyphs instead of to the
 * whole card. Only text of the expected tone gets it, light text under a dark halo in the dark
 * theme and dark text under a light one otherwise; accent-coloured text (app name, actions) and
 * text that already carries a shadow are left alone. Applied to the inflated views, never to the
 * RemoteViews; removing it restores each view's own shadow. Main thread only.
 */
final class TextHalo {
    private TextHalo() {}
    /** Views this class gave a shadow to, with the shadow they had: radius, dx, dy, colour. */
    private static final WeakHashMap<TextView, Own> haloed = new WeakHashMap<>();
    private static final class Own {
        final float radius, dx, dy; final int color;
        Own(TextView t) { radius = t.getShadowRadius(); dx = t.getShadowDx(); dy = t.getShadowDy(); color = t.getShadowColor(); }
    }
    /** Blur radius of the halo in dp. */
    static final float RADIUS_DP = 2.5f;

    /** alpha 0 removes the halo from every view under {@code root}. Returns the views changed. */
    static int apply(View root, float alpha, boolean lightText) {
        if (root == null) return 0;
        int color = alpha <= 0f ? 0 : Color.argb(Math.round(255f * Math.min(1f, alpha)), lightText ? 0 : 255, lightText ? 0 : 255, lightText ? 0 : 255);
        float radius = RADIUS_DP * root.getResources().getDisplayMetrics().density;
        return walk(root, color, radius, lightText);
    }
    private static int walk(View v, int color, float radius, boolean lightText) {
        int changed = 0;
        if (v instanceof TextView) changed += text((TextView) v, color, radius, lightText) ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                // A group's child rows are cards of their own, with their own halo.
                if (c.getClass().getName().endsWith("ExpandableNotificationRow")) continue;
                changed += walk(c, color, radius, lightText);
            }
        }
        return changed;
    }
    private static boolean text(TextView t, int color, float radius, boolean lightText) {
        Own own = haloed.get(t);
        if (color == 0) {
            if (own == null) return false;
            haloed.remove(t);
            t.setShadowLayer(own.radius, own.dx, own.dy, own.color);
            return true;
        }
        if (own == null) {
            if (t.getShadowRadius() > 0f || !expectedTone(t.getCurrentTextColor(), lightText)) return false;
            haloed.put(t, new Own(t));
        }
        if (t.getShadowColor() == color && t.getShadowRadius() == radius) return false;
        t.setShadowLayer(radius, 0f, 0f, color);
        return true;
    }
    /** Whether a text colour is the theme's notification text tone (near white or near black, greys included). */
    static boolean expectedTone(int c, boolean lightText) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > 40) return false;
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return lightText ? luma >= 0.55f : luma <= 0.45f;
    }
}

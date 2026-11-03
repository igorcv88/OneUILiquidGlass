package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import java.lang.reflect.Method;

/**
 * Corner radii and fill colour of the background Samsung wrote, read from the drawable itself
 * (GradientDrawable from setChipBg/changeCardBg, a toast shape), from a CardView-like host
 * (getRadius/getCardBackgroundColor) or from the laid-out outline. Nothing is guessed: no radius
 * found means the host stays native.
 */
public final class NativeShape {
    private NativeShape() {}

    /** The drawable that carries the shape, through insets, single-layer stacks and state lists. */
    static Drawable unwrap(Drawable d) {
        for (int i = 0; i < 6 && d != null; i++) {
            if (d instanceof RippleDrawable) return d;
            if (d instanceof InsetDrawable || d instanceof DrawableWrapper) {
                Drawable inner = d instanceof InsetDrawable ? ((InsetDrawable) d).getDrawable() : ((DrawableWrapper) d).getDrawable();
                if (inner == null) return d;
                d = inner;
            } else if (d instanceof LayerDrawable && ((LayerDrawable) d).getNumberOfLayers() == 1) {
                d = ((LayerDrawable) d).getDrawable(0);
            } else if (d.getCurrent() != null && d.getCurrent() != d) {
                d = d.getCurrent();
            } else return d;
        }
        return d;
    }

    /** A native background with touch feedback keeps it: the glass would drop the ripple. */
    static boolean interactive(Drawable d) {
        return d instanceof RippleDrawable || unwrap(d) instanceof RippleDrawable;
    }

    /** Eight radii (px) or null. */
    static float[] radii(Drawable background, View host) {
        Drawable d = unwrap(background);
        if (d instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) d;
            float[] r = null;
            try { r = g.getCornerRadii(); } catch (RuntimeException ignored) { }
            if (r != null && r.length == 8) return r.clone();
            float one = g.getCornerRadius();
            if (one > 0f) return uniform(one);
            // A plain rectangle: square corners are still a known shape.
            if (g.getShape() == GradientDrawable.RECTANGLE) return uniform(0f);
        }
        Float card = floatCall(host, "getRadius");
        if (card != null && card >= 0f) return uniform(card);
        if (host != null && host.getWidth() > 0 && host.getHeight() > 0) {
            Outline o = new Outline();
            if (background != null) {
                background.setBounds(0, 0, host.getWidth(), host.getHeight());
                background.getOutline(o);
                float r = o.getRadius();
                if (r >= 0f && !o.isEmpty()) return uniform(r);
            }
            if (host.getOutlineProvider() != null) {
                o = new Outline();
                host.getOutlineProvider().getOutline(host, o);
                float r = o.getRadius();
                if (r >= 0f && !o.isEmpty()) return uniform(r);
            }
        }
        return null;
    }

    /** The native fill colour, or null: background tint, shape colour, card colour, plain colour. */
    static Integer color(Drawable background, View host) {
        // CardView's own background may be a ColorDrawable/RoundRectDrawable that does not reflect
        // setCardBackgroundColor(). Use the public CardView colour as the authoritative source.
        Object card = call(host, "getCardBackgroundColor");
        if (card instanceof ColorStateList) return state((ColorStateList) card, host);
        if (host != null && host.getBackgroundTintList() != null) return state(host.getBackgroundTintList(), host);
        Drawable d = unwrap(background);
        if (d instanceof GradientDrawable) {
            ColorStateList c = ((GradientDrawable) d).getColor();
            if (c != null) return state(c, host);
        }
        if (d instanceof ColorDrawable) return ((ColorDrawable) d).getColor();
        return null;
    }

    /** Whether a host is a CardView-like view: its background must stay its own drawable class. */
    static boolean cardLike(View host) {
        if (host == null) return false;
        for (Method m : host.getClass().getMethods())
            if (m.getName().equals("setCardBackgroundColor")) return true;
        return false;
    }

    static float[] uniform(float r) { float[] a = new float[8]; java.util.Arrays.fill(a, r); return a; }
    private static int state(ColorStateList c, View host) {
        return host == null ? c.getDefaultColor() : c.getColorForState(host.getDrawableState(), c.getDefaultColor());
    }
    static Object call(Object o, String name) {
        if (o == null) return null;
        Method m = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.method(o.getClass(), name);
        if (m == null) return null;
        try { return m.invoke(o); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    private static Float floatCall(Object o, String name) {
        Object v = call(o, name);
        return v instanceof Float ? (Float) v : null;
    }
}

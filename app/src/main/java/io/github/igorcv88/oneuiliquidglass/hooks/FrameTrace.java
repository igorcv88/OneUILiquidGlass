package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.Locale;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Per-frame state of a notification card while it moves (debug.oulg.trace=1 only), logged when it
 * changes: everything that can fade or cut a card without the view alpha the blur region follows.
 * Ancestor alpha, transition alpha and scale; the native drawable's alpha; the background view's
 * clip and actual height; the row's visibility, shelf state, content transformation and the alpha
 * and visibility of its content views; the alpha the blur region carries.
 */
final class FrameTrace {
    private FrameTrace() {}
    private static final String[] ROW_FIELDS = {"mInShelf", "mContentTransformationAmount", "mClipTopAmount", "mClipBottomAmount",
            "mActualHeight", "mIsChildInGroup", "mChildrenExpanded", "mHeadsUpAnimatingAway", "mIsSystemChildExpanded"};
    private static final String[] CONTENT_FIELDS = {"mPrivateLayout", "mChildrenContainer", "mPublicLayout"};

    /** One line describing the card now; bg is the NotificationBackgroundView (or the shelf's background). */
    static String describe(View bg, View row, Drawable nativeDrawable) {
        StringBuilder out = new StringBuilder();
        float a = 1f, ta = 1f, sx = 1f, sy = 1f;
        for (Object p = bg; p instanceof View; p = ((View) p).getParent()) {
            View v = (View) p;
            a *= v.getAlpha(); ta *= v.getTransitionAlpha(); sx *= v.getScaleX(); sy *= v.getScaleY();
        }
        out.append("a=").append(f(a)).append(" ta=").append(f(ta)).append(" s=").append(f(sx)).append('x').append(f(sy));
        out.append(" shown=").append(bg.isShown()).append(" vis=").append(bg.getVisibility()).append(" h=").append(bg.getHeight());
        Drawable region = bg.getBackground();
        out.append(" region=").append(region == null ? "null" : region.getClass().getSimpleName() + "@" + region.getAlpha());
        if (nativeDrawable != null) out.append(" native=").append(nativeDrawable.getAlpha());
        out.append(" bgClip=").append(Reflect.read(bg, "mClipTopAmount")).append('/').append(Reflect.read(bg, "mClipBottomAmount"))
                .append(" bgActual=").append(Reflect.read(bg, "mActualHeight"));
        if (row != null) {
            out.append(" row=").append(row.getClass().getSimpleName()).append(" rowVis=").append(row.getVisibility())
                    .append(" rowA=").append(f(row.getAlpha())).append(" rowTy=").append(Math.round(row.getTranslationY()));
            for (String name : ROW_FIELDS) {
                Object value = Reflect.read(row, name);
                if (value != null) out.append(' ').append(name.substring(1)).append('=').append(value instanceof Float ? f((Float) value) : value);
            }
            for (String name : CONTENT_FIELDS) {
                Object c = Reflect.read(row, name);
                if (c instanceof View) {
                    View cv = (View) c;
                    out.append(' ').append(name.substring(1)).append('=').append(cv.getVisibility()).append('@').append(f(cv.getAlpha()));
                }
            }
        }
        return out.toString();
    }
    static void log(String event, View bg, String line) {
        Probe.log(event, "viewId=" + Integer.toHexString(System.identityHashCode(bg)) + " " + line);
    }
    private static String f(float v) { return String.format(Locale.ROOT, "%.2f", v); }
}

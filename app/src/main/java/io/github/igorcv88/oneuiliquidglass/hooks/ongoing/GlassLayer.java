package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;

/**
 * Background-only layer the Now Bar glass is drawn on, added inside the card's CardView (DZIF:
 * getCardViewContainer() and getBlurContainer() are the same CardView, whose own background
 * cannot be replaced). It is laid out over the CardView's whole frame regardless of its content
 * padding, drawn behind Samsung's content (negative Z, so getChildAt(0) still returns Samsung's
 * view), and is invisible to touch and accessibility. It never draws anything but its background.
 */
final class GlassLayer extends View {
    GlassLayer(Context c) {
        super(c);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setTranslationZ(-1f);
        setSaveEnabled(false);
    }
    /** The parent's full frame: the glass has the card's geometry, not its padded content box. */
    @Override public void layout(int l, int t, int r, int b) {
        ViewParent p = getParent();
        if (p instanceof View) super.layout(0, 0, ((View) p).getWidth(), ((View) p).getHeight());
        else super.layout(l, t, r, b);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e) { return false; }
    @Override public boolean hasOverlappingRendering() { return false; }
}

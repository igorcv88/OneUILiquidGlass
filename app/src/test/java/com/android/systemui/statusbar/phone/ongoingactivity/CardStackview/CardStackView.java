package com.android.systemui.statusbar.phone.ongoingactivity.CardStackview;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Test double with the DZIF layering: each card is wrapped in a ripple frame and a blur frame
 * (setBlurBG installs a BlurDrawable there); changeCardBg rebuilds the card fill.
 */
public class CardStackView extends FrameLayout {
    /**
     * Stands in for com.android.internal.graphics.drawable.BackgroundBlurDrawable as AOSP writes it:
     * the colour lives in a Paint (mPaint), setColor does not invalidate, and there is no getColor().
     * Dark and almost opaque, as the expanded card looked on DZIF (navy over a colourful wallpaper).
     */
    public static class NativeBackgroundBlurDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint mPaint = new android.graphics.Paint();
        public int invalidations;
        public NativeBackgroundBlurDrawable() { mPaint.setColor(0xe6141428); }
        public void setColor(int c) { mPaint.setColor(c); }
        /** Test accessor only (not getColor: the real class has none). */
        public int color() { return mPaint.getColor(); }
        @Override public void invalidateSelf() { invalidations++; super.invalidateSelf(); }
        @Override public void draw(android.graphics.Canvas c) { }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    /** The blur frame Samsung wraps each card in; counts redraw requests. */
    public static class BlurFrame extends FrameLayout {
        public int invalidations;
        public BlurFrame(Context c) { super(c); }
        @Override public void invalidate() { invalidations++; super.invalidate(); }
    }
    public CardStackView(Context c) { super(c); }
    /** Wraps a card root as Samsung does and returns its blur frame. */
    public FrameLayout addCard(View root) {
        FrameLayout ripple = new FrameLayout(getContext());
        ripple.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null, null));
        FrameLayout blur = new BlurFrame(getContext());
        ripple.addView(blur, new LayoutParams(-1, -1));
        blur.addView(root, new LayoutParams(-1, -1));
        addView(ripple, new LayoutParams(-1, -1));
        return blur;
    }
    public void changeCardBg(View fill, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(56f);
        d.setColor(color);
        fill.setBackground(d);
    }
    public void setBlurBG(View frame, int index) { frame.setBackground(new NativeBackgroundBlurDrawable()); }
    public void updateItemBg() {
        for (int i = 0; i < getChildCount(); i++) {
            View card = ((FrameLayout) ((FrameLayout) getChildAt(i)).getChildAt(0)).getChildAt(0);
            if (card instanceof CardItemView) changeCardBg(((CardItemView) card).fill(), 0xff3060d0);
        }
    }
}

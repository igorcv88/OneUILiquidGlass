package com.android.systemui.statusbar.phone.ongoingactivity.CardStackview;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;

/** Test double: changeCardBg recreates the GradientDrawable of one card, updateItemBg of all. */
public class CardStackView extends FrameLayout {
    public CardStackView(Context c) { super(c); }
    public void changeCardBg(View card, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(56f);
        d.setColor(color);
        card.setBackground(d);
    }
    public void setBlurBG(View card, int index) { }
    public void updateItemBg() { for (int i = 0; i < getChildCount(); i++) changeCardBg(getChildAt(i), 0xff3060d0); }
}

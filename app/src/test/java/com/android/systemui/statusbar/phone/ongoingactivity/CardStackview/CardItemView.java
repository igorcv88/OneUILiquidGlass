package com.android.systemui.statusbar.phone.ongoingactivity.CardStackview;

import android.content.Context;
import android.widget.FrameLayout;

/** Test double: the card root the adapter returns on DZIF; it has no background of its own. */
public class CardItemView extends FrameLayout {
    public CardItemView(Context c) { super(c); }
    public FrameLayout fill() { return (FrameLayout) getChildAt(0); }
}

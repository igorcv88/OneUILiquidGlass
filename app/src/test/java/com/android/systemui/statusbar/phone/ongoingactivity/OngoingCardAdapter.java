package com.android.systemui.statusbar.phone.ongoingactivity;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardItemView;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double of the DZIF expanded-card adapter: getView returns a CardItemView without
 * background whose match_parent child carries the opaque GradientDrawable fill (window survey,
 * 02/11/2026). isMediaView is the native verdict.
 */
public class OngoingCardAdapter {
    public final List<OngoingActivityData> mList = new ArrayList<>();
    public final java.util.Set<View> mediaViews = new java.util.HashSet<>();
    public View getView(int position, View convert, ViewGroup parent) {
        CardItemView v;
        if (convert instanceof CardItemView) v = (CardItemView) convert;
        else {
            v = new CardItemView(parent.getContext());
            v.addView(new FrameLayout(parent.getContext()), new FrameLayout.LayoutParams(-1, -1));
        }
        bindView(v, position);
        return v;
    }
    public void bindView(View v, int position) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(56f);
        d.setColor(0xff2050c0);
        ((CardItemView) v).fill().setBackground(d);
    }
    public boolean isMediaView(View v) { return mediaViews.contains(v); }
    public void initNormalNotificationView(View v) { }
}

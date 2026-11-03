package com.android.systemui.statusbar.phone.ongoingactivity;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** Test double of the DZIF expanded-card adapter; isMediaView is the native verdict. */
public class OngoingCardAdapter {
    public final List<OngoingActivityData> mList = new ArrayList<>();
    public final java.util.Set<View> mediaViews = new java.util.HashSet<>();
    public View getView(int position, View convert, ViewGroup parent) {
        View v = convert != null ? convert : new FrameLayout(parent.getContext());
        bindView(v, position);
        return v;
    }
    public void bindView(View v, int position) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(56f);
        d.setColor(0xff2050c0);
        v.setBackground(d);
    }
    public boolean isMediaView(View v) { return mediaViews.contains(v); }
    public void initNormalNotificationView(View v) { }
}

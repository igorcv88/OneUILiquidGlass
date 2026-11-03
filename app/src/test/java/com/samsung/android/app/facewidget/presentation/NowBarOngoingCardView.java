package com.samsung.android.app.facewidget.presentation;

import android.content.Context;
import com.android.systemui.plugins.keyguardstatusview.NowBarItem;
import com.samsung.android.app.facewidget.common.NowBarConstants;

/** Test double of the ongoing (non-media) Now Bar card. */
public class NowBarOngoingCardView extends a {
    public NowBarOngoingCardView(Context c) { super(c); }
    public void d0(NowBarItem item) { N(item, container, 0f, 0xffe07020); }
    public void e0(NowBarItem item, boolean animate) { d0(item); }
    public void setViewMode(NowBarConstants.Companion.ViewMode mode) { }
    public void setExpandedViewColor(float f) { }
}

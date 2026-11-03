package com.android.systemui.statusbar.phone.ongoingactivity;

/** Test double: the fields of DZIF's OngoingActivityData that the ongoing hooks read. */
public class OngoingActivityData {
    public boolean mIsMediaOngoingData;
    public boolean mIsAutomation;
    public String mPackageName;
    public Object mNotificationEntry;
    public OngoingActivityData(String pkg, boolean media) { mPackageName = pkg; mIsMediaOngoingData = media; }
}

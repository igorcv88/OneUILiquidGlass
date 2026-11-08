package com.android.systemui.statusbar.phone.ongoingactivity;

/** Only the non-mutating Samsung removal callback is relevant to the diagnostic hook. */
public final class OngoingActivityController {
    public int removed;
    public void onEntryRemoved(Object entry) { removed++; }
    public void onEntryUpdated(Object entry) { }
}

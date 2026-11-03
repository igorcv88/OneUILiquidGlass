package com.android.systemui.statusbar.phone.ongoingactivity;

import java.util.ArrayList;
import java.util.List;

/** Test double of DZIF's data helper: the chip adapter reads its data only through getDataByIndex. */
public class OngoingActivityDataHelper {
    private static final List<OngoingActivityData> DATA = new ArrayList<>();
    public static void reset() { DATA.clear(); }
    public static void add(OngoingActivityData d) { DATA.add(d); }
    public static void set(int i, OngoingActivityData d) { DATA.set(i, d); }
    public static int getDataCount() { return DATA.size(); }
    public static OngoingActivityData getDataByIndex(int index) { return index >= 0 && index < DATA.size() ? DATA.get(index) : null; }
}

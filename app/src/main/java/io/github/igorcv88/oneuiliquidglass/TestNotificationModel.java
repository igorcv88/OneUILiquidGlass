package io.github.igorcv88.oneuiliquidglass;

import android.content.Context;

/** Shared by offline samples and real NotificationManager payloads. */
public final class TestNotificationModel {
    public static String title(Context c) { return c.getString(R.string.test_notification_title); }
    public static String summary(Context c) { return c.getString(R.string.test_notification_summary); }
    public static String body(Context c) { return c.getString(R.string.test_notification_body); }
    public static String open(Context c) { return c.getString(R.string.test_notification_open); }
    public static String dismiss(Context c) { return c.getString(R.string.test_notification_dismiss); }
    private TestNotificationModel() {}
}

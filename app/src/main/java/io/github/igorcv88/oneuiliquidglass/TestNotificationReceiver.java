package io.github.igorcv88.oneuiliquidglass;

import android.app.NotificationManager;
import android.content.*;

public final class TestNotificationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        int id = intent.getIntExtra("id", -1);
        if ("dismiss".equals(intent.getAction())
                && id >= TestNotifications.FIRST
                && id <= TestNotifications.LAST)
            c.getSystemService(NotificationManager.class).cancel(id);
    }
}

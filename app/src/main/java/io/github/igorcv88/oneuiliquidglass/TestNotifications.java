package io.github.igorcv88.oneuiliquidglass;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** All test IDs belong to this app. Importance requests heads-up; SystemUI makes the decision. */
public final class TestNotifications {
    public static final String CHANNEL = "oulg-material-tests-v2";
    public static final int FIRST = 4100, LAST = 4102;

    public static void channel(Context c) {
        c.getSystemService(NotificationManager.class)
                .createNotificationChannel(
                        new NotificationChannel(
                                CHANNEL, "Testes de vidro", NotificationManager.IMPORTANCE_HIGH));
    }

    public static String status(Context c) {
        channel(c);
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (!m.areNotificationsEnabled())
            return "Notificações bloqueadas. Conceda a permissão nos ajustes.";
        NotificationChannel ch = m.getNotificationChannel(CHANNEL);
        if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE)
            return "Canal de testes bloqueado.";
        return "Canal: "
                + ch.getImportance()
                + " · DND: "
                + m.getCurrentInterruptionFilter()
                + ". O Android decide se mostra pop-up.";
    }

    @android.annotation.SuppressLint("MissingPermission")
    public static void post(Context c, int count, boolean expanded) {
        channel(c);
        clear(c);
        for (int i = 0; i < Math.min(3, count); i++) {
            int id = FIRST + i;
            Intent open =
                    new Intent(c, MainActivity.class)
                            .putExtra("destination", "surfaces")
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content =
                    PendingIntent.getActivity(
                            c,
                            id,
                            open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent dismiss =
                    PendingIntent.getBroadcast(
                            c,
                            id,
                            new Intent(c, TestNotificationReceiver.class)
                                    .setAction("dismiss")
                                    .putExtra("id", id),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b =
                    new Notification.Builder(c, CHANNEL)
                            .setSmallIcon(R.drawable.oulg_notification)
                            .setLargeIcon(brand(c))
                            .setContentTitle(TestNotificationModel.TITLE)
                            .setContentText(TestNotificationModel.SUMMARY)
                            .setWhen(System.currentTimeMillis())
                            .setShowWhen(true)
                            .setContentIntent(content)
                            .setAutoCancel(true)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setTimeoutAfter(10 * 60 * 1000L)
                            .addAction(
                                    new Notification.Action.Builder(
                                                    null, TestNotificationModel.OPEN, content)
                                            .build())
                            .addAction(
                                    new Notification.Action.Builder(
                                                    null, TestNotificationModel.DISMISS, dismiss)
                                            .build());
            if (expanded)
                b.setStyle(new Notification.BigTextStyle().bigText(TestNotificationModel.BODY));
            c.getSystemService(NotificationManager.class).notify(id, b.build());
        }
    }

    public static void clear(Context c) {
        for (int id = FIRST; id <= LAST; id++)
            c.getSystemService(NotificationManager.class).cancel(id);
    }

    private static Bitmap brand(Context context) {
        Drawable drawable = context.getDrawable(R.drawable.oulg_launcher);
        Bitmap image = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
        if (drawable != null) {
            drawable.setBounds(0, 0, 144, 144);
            drawable.draw(new Canvas(image));
        }
        return image;
    }

    private TestNotifications() {}
}

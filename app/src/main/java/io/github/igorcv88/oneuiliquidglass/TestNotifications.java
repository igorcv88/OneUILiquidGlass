package io.github.igorcv88.oneuiliquidglass;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

/**
 * Real notifications posted by this app (own IDs, own channel). Importance asks for a pop-up;
 * SystemUI decides. Every call returns what happened so the button can always say something
 * true.
 */
public final class TestNotifications {
    public static final String CHANNEL = "oulg-material-tests-v2";
    public static final int FIRST = 4100, LAST = 4102;

    public enum Outcome {
        /** Posted; nothing known that would hide it. */
        POSTED,
        /** Posted while Do Not Disturb filters interruptions: the pop-up may not appear. */
        POSTED_DND,
        /** App notifications are off. */
        BLOCKED_APP,
        /** The test channel is off. */
        BLOCKED_CHANNEL,
        /** The channel no longer allows pop-ups (lowered by the person). */
        POSTED_QUIET,
        /** Scheduled for later. */
        SCHEDULED,
        FAILED
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable scheduled;

    public static void channel(Context c) {
        NotificationChannel ch =
                new NotificationChannel(CHANNEL, "Testes de vidro", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notificações de teste do One UI Liquid Glass");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /** What would stop a test from showing, without posting anything. */
    public static Outcome readiness(Context c) {
        channel(c);
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (!m.areNotificationsEnabled()) return Outcome.BLOCKED_APP;
        NotificationChannel ch = m.getNotificationChannel(CHANNEL);
        if (ch == null || ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return Outcome.BLOCKED_CHANNEL;
        if (ch.getImportance() < NotificationManager.IMPORTANCE_HIGH) return Outcome.POSTED_QUIET;
        if (m.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL)
            return Outcome.POSTED_DND;
        return Outcome.POSTED;
    }

    @android.annotation.SuppressLint("MissingPermission")
    public static Outcome post(Context c, int count, boolean expanded) {
        Outcome ready = readiness(c);
        if (ready == Outcome.BLOCKED_APP || ready == Outcome.BLOCKED_CHANNEL) return ready;
        try {
            clearPosted(c);
            for (int i = 0; i < Math.min(3, count); i++) {
                int id = FIRST + i;
                Intent open =
                        new Intent(c, MainActivity.class)
                                .putExtra("destination", "surfaces")
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent content = PendingIntent.getActivity(c, id, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                PendingIntent dismiss = PendingIntent.getBroadcast(c, id,
                        new Intent(c, TestNotificationReceiver.class).setAction("dismiss").putExtra("id", id),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification.Builder b = new Notification.Builder(c, CHANNEL)
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
                        .addAction(new Notification.Action.Builder(null, TestNotificationModel.OPEN, content).build())
                        .addAction(new Notification.Action.Builder(null, TestNotificationModel.DISMISS, dismiss).build());
                if (expanded) b.setStyle(new Notification.BigTextStyle().bigText(TestNotificationModel.BODY));
                c.getSystemService(NotificationManager.class).notify(id, b.build());
            }
            return ready;
        } catch (RuntimeException e) {
            return Outcome.FAILED;
        }
    }

    /**
     * Posts after {@code delayMs} from an app-scoped handler, so it survives leaving or
     * recreating the screen while the process lives. {@code done} runs on the main thread.
     */
    public static void schedule(Context context, int count, long delayMs, java.util.function.Consumer<Outcome> done) {
        Context app = context.getApplicationContext();
        cancelScheduled();
        scheduled = () -> {
            scheduled = null;
            Outcome o = post(app, count, true);
            if (done != null) done.accept(o);
        };
        MAIN.postDelayed(scheduled, delayMs);
    }

    public static boolean hasScheduled() {
        return scheduled != null;
    }

    public static void cancelScheduled() {
        if (scheduled != null) MAIN.removeCallbacks(scheduled);
        scheduled = null;
    }

    /** Cancels this app's tests and any scheduled one; never touches other apps. */
    public static void clear(Context c) {
        cancelScheduled();
        clearPosted(c);
    }

    private static void clearPosted(Context c) {
        for (int id = FIRST; id <= LAST; id++) c.getSystemService(NotificationManager.class).cancel(id);
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

    /** Short, true sentence for the result of a test. */
    public static String describe(Outcome o) {
        switch (o) {
            case POSTED: return "Enviada. Se o pop-up não aparecer, abra a central.";
            case POSTED_DND: return "Enviada, mas o Não perturbe pode esconder o pop-up.";
            case POSTED_QUIET: return "Enviada sem pop-up: o canal de teste está silencioso.";
            case BLOCKED_APP: return "As notificações do app estão desativadas.";
            case BLOCKED_CHANNEL: return "O canal de teste está desativado.";
            case SCHEDULED: return "Chega em 5 segundos. Saia do app para ver o pop-up.";
            default: return "Não foi possível enviar a notificação.";
        }
    }

    private TestNotifications() {}
}

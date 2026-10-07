package io.github.igorcv88.oneuiliquidglass;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = Config.open(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("One UI Liquid Glass 0.1\n\nEnable this module for SystemUI in LSPosed. Restart SystemUI after changing the mode.\n\nProbe mode logs firmware classes and notification lifecycle. Glass mode adds compositor blur and edge lighting to detailed heads-up notifications. Brief popups are diagnostic only.\n");
        layout.addView(title);
        if (!Config.lsposedPrefs) {
            TextView warning = new TextView(this);
            warning.setText("LSPosed preferences bridge is not active for this app. SystemUI reads the switch through the module's config provider instead (log: CONFIG_PROVIDER).\n");
            layout.addView(warning);
        }
        Switch enabled = new Switch(this);
        enabled.setText("Experimental glass (off = probe only)");
        enabled.setChecked(prefs.getBoolean(Config.KEY_ENABLED, false));
        enabled.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(Config.KEY_ENABLED, checked).apply());
        layout.addView(enabled);
        Button test = new Button(this);
        test.setText("Post test notification in 5 seconds");
        test.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("heads-up-test-v1", "Heads-up test", NotificationManager.IMPORTANCE_HIGH));
            new Handler(Looper.getMainLooper()).postDelayed(() -> manager.notify(101,
                    new Notification.Builder(this, "heads-up-test-v1")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Liquid Glass test")
                        .setContentText("Expand, move and dismiss this notification.")
                        .setStyle(new Notification.BigTextStyle().bigText("This is a real notification. Test expansion, QS, keyboard, rotation and dismissal over light and dark app content."))
                        .setAutoCancel(true).build()), 5000);
            test.setText("Scheduled. Switch to another app.");
        });
        layout.addView(test);
        TextView note = new TextView(this);
        note.setText("\nSamsung notification settings must use Detailed pop-up style. Channel importance, DND and lockscreen settings can suppress heads-up presentation.\n\nLog tag: OULG. No notification text, package or key is logged. To recover, disable this module in LSPosed and restart SystemUI.");
        layout.addView(note);
        setContentView(layout);
    }
}

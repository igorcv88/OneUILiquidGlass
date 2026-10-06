package io.github.igorcv88.oneuiliquidglass;

import android.Manifest;
import android.annotation.SuppressLint;
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
        SharedPreferences prefs = modulePrefs();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("One UI Liquid Glass 0.1\n\nEnable this module for SystemUI in LSPosed. Restart SystemUI after changing the mode.\n\nProbe mode logs firmware classes and notification lifecycle. Glass mode adds compositor blur and edge lighting to detailed heads-up notifications. Brief popups are diagnostic only.\n");
        layout.addView(title);
        if (!lsposedPrefs) {
            TextView warning = new TextView(this);
            warning.setText("LSPosed is not active for this module. The switch below is stored locally only and SystemUI will keep reading enabled=false.\n");
            layout.addView(warning);
        }
        Switch enabled = new Switch(this);
        enabled.setText("Experimental glass (off = probe only)");
        enabled.setChecked(prefs.getBoolean("enabled", false));
        enabled.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("enabled", checked).apply());
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

    private boolean lsposedPrefs;

    /**
     * SystemUI reads this file through XSharedPreferences. LSPosed (xposedsharedprefs) only
     * redirects it to a SystemUI-readable location for MODE_WORLD_READABLE; MODE_PRIVATE stays
     * in the app's data directory, which SELinux denies to SystemUI. Without an active LSPosed
     * hook the framework throws SecurityException for MODE_WORLD_READABLE.
     */
    @SuppressWarnings("deprecation")
    @SuppressLint("WorldReadableFiles")
    private SharedPreferences modulePrefs() {
        try {
            SharedPreferences prefs = getSharedPreferences("glass", MODE_WORLD_READABLE);
            lsposedPrefs = true;
            return prefs;
        } catch (SecurityException e) {
            lsposedPrefs = false;
            return getSharedPreferences("glass", MODE_PRIVATE);
        }
    }
}

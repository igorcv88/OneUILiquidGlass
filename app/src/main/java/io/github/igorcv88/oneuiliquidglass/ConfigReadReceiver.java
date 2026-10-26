package io.github.igorcv88.oneuiliquidglass;

import android.content.*;

/**
 * STATUS_BAR permission on the manifest receiver restricts acknowledgements to privileged senders.
 */
public final class ConfigReadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"io.github.igorcv88.oneuiliquidglass.CONFIG_READ".equals(intent.getAction())) return;
        String value = intent.getStringExtra("generation");
        try {
            long generation = Long.parseLong(value);
            if (generation < 0) return;
            context.getSharedPreferences("engine_status", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("readGeneration", generation)
                    .putLong("readAt", System.currentTimeMillis())
                    .apply();
        } catch (RuntimeException ignored) {
        }
    }
}

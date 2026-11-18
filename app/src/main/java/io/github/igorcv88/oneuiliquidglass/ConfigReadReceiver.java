package io.github.igorcv88.oneuiliquidglass;

import android.content.*;
import io.github.igorcv88.oneuiliquidglass.config.EnergyGovernor;
import io.github.igorcv88.oneuiliquidglass.config.EnergyReport;

/**
 * STATUS_BAR permission on the manifest receiver restricts acknowledgements to privileged senders.
 * Two actions: CONFIG_READ (SystemUI read the snapshot) and ENERGY_STATE (the energy policy state).
 */
public final class ConfigReadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (EnergyGovernor.ACTION_ENERGY_STATE.equals(action)) {
            storeEnergy(context, intent);
            return;
        }
        if (!"io.github.igorcv88.oneuiliquidglass.CONFIG_READ".equals(action)) return;
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

    /** Invalid reports are dropped whole; the last valid one stays. */
    private static void storeEnergy(Context context, Intent intent) {
        try {
            EnergyReport report = EnergyReport.fromIntent(intent);
            if (report == null) return;
            report.write(context.getSharedPreferences("engine_status", Context.MODE_PRIVATE).edit()).apply();
        } catch (RuntimeException ignored) {
            // A malformed extras bundle is a dropped report, not a crash.
        }
    }
}

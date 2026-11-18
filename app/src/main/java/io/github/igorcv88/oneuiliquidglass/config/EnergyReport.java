package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Intent;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy;

/**
 * The last energy state SystemUI reported to the app (ENERGY_STATE), as kept under engine_status
 * energy.*. {@code at == 0} means nothing was ever reported: the state is unknown, never "active".
 */
public final class EnergyReport {
    public final EnergyPolicy.Reason reason;
    public final int mask, level;
    public final boolean charging;
    public final long at;

    public EnergyReport(EnergyPolicy.Reason reason, int mask, int level, boolean charging, long at) {
        this.reason = reason;
        this.mask = mask;
        this.level = level;
        this.charging = charging;
        this.at = at;
    }

    public static EnergyReport unknown() {
        return new EnergyReport(EnergyPolicy.Reason.NONE, 0, -1, false, 0);
    }

    public boolean known() {
        return at > 0;
    }

    public boolean paused() {
        return reason != EnergyPolicy.Reason.NONE;
    }

    /**
     * Validates an ENERGY_STATE intent: reason is one of the policy reasons, 0 &lt;= mask &lt;= 127,
     * -1 &lt;= level &lt;= 100, a boolean and a positive timestamp. Anything else is null (ignored).
     */
    public static EnergyReport fromIntent(Intent intent) {
        if (intent == null || !EnergyGovernor.ACTION_ENERGY_STATE.equals(intent.getAction())) return null;
        String name = intent.getStringExtra("reason");
        int mask = intent.getIntExtra("mask", Integer.MIN_VALUE);
        int level = intent.getIntExtra("level", Integer.MIN_VALUE);
        long at = intent.getLongExtra("at", -1L);
        if (name == null || !intent.hasExtra("charging") || !(intent.getExtras().get("charging") instanceof Boolean))
            return null;
        EnergyPolicy.Reason reason;
        try {
            reason = EnergyPolicy.Reason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (mask < 0 || mask > EnergyPolicy.ALL_SURFACES || level < -1 || level > 100 || at <= 0) return null;
        return new EnergyReport(reason, mask, level, intent.getBooleanExtra("charging", false), at);
    }

    public static EnergyReport read(SharedPreferences prefs) {
        long at = prefs.getLong("energy.at", 0);
        if (at <= 0) return unknown();
        EnergyPolicy.Reason reason;
        try {
            reason = EnergyPolicy.Reason.valueOf(prefs.getString("energy.reason", "NONE"));
        } catch (IllegalArgumentException e) {
            return unknown();
        }
        return new EnergyReport(reason, prefs.getInt("energy.mask", 0), prefs.getInt("energy.level", -1),
                prefs.getBoolean("energy.charging", false), at);
    }

    public SharedPreferences.Editor write(SharedPreferences.Editor editor) {
        return editor.putString("energy.reason", reason.name()).putInt("energy.mask", mask)
                .putInt("energy.level", level).putBoolean("energy.charging", charging).putLong("energy.at", at);
    }
}

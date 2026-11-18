package io.github.igorcv88.oneuiliquidglass.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Decision;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Input;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Applies the user's energy policy inside SystemUI (docs/OULG_UI_PLAN/ENERGY_POLICY.md section 5).
 * Event driven only: the sticky battery intent and the Battery saver broadcast, registered once,
 * not exported, on the config worker. No polling, alarms or wakelocks. A surface is suspended only
 * by masking {@link ConfigClient#flag}; nothing is ever written to the user's saved flags.
 */
public final class EnergyGovernor {
    public static final String ACTION_ENERGY_STATE = "io.github.igorcv88.oneuiliquidglass.ENERGY_STATE";

    /** Bit of the suspended-surface mask to the product flag that surface reads. */
    static final Map<String, Integer> FLAG_BITS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("surface.headsup.enabled", EnergyPolicy.BIT_HEADSUP);
        m.put("surface.keyguard.enabled", EnergyPolicy.BIT_KEYGUARD);
        m.put("surface.shade.enabled", EnergyPolicy.BIT_SHADE);
        m.put(ProductFlags.QUICK_SETTINGS, EnergyPolicy.BIT_QUICK_SETTINGS);
        m.put(ProductFlags.LIVE_ACTIVITY, EnergyPolicy.BIT_LIVE_ACTIVITY);
        m.put(ProductFlags.NOW_BAR, EnergyPolicy.BIT_NOW_BAR);
        m.put(ProductFlags.TOAST, EnergyPolicy.BIT_TOAST);
        FLAG_BITS = Collections.unmodifiableMap(m);
    }

    /** Receives the outcome of an evaluation. */
    public interface Publisher {
        /** The suspended-surface mask changed: publish it and refresh the surfaces. */
        void maskChanged(int mask);

        /** The decision changed (or this is the first reading): tell the app. */
        void report(Decision decision, Input input);
    }

    private final Function<String, String> snapshot;
    private final Publisher publisher;
    private Input input;
    private Decision decision = Decision.NONE;
    private int publishedMask;

    public EnergyGovernor(Function<String, String> snapshot, Publisher publisher) {
        this.snapshot = snapshot;
        this.publisher = publisher;
    }

    /** The mask bit of a product flag, 0 when the flag is not a suspendable surface. */
    public static int bitOf(String flagKey) {
        Integer bit = FLAG_BITS.get(flagKey);
        return bit == null ? 0 : bit;
    }

    public static boolean suspended(int mask, String flagKey) {
        return (mask & bitOf(flagKey)) != 0;
    }

    /** Level in percent (-1 unknown), presence and plug from the battery intent; saver from the power manager. */
    public static Input parse(Intent battery, boolean saver) {
        if (battery == null) return new Input(-1, false, false, saver);
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = level < 0 || scale <= 0 ? -1 : Math.min(100, level * 100 / scale);
        boolean present = battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        boolean charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return new Input(percent, present, charging, saver);
    }

    /** Registers the two system broadcasts once and evaluates the current (sticky) state at once. */
    public void start(Context app, Handler worker) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    onState(read(app));
                } catch (RuntimeException e) {
                    Probe.error("ENERGY_EVENT_FAILED", e);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            app.registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
            onState(read(app));
        } catch (RuntimeException e) {
            Probe.error("ENERGY_START_FAILED", e);
        }
    }

    private static Input read(Context app) {
        Intent sticky = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        PowerManager pm = app.getSystemService(PowerManager.class);
        return parse(sticky, pm != null && pm.isPowerSaveMode());
    }

    /** A battery or saver reading. An unchanged tuple is ignored. */
    synchronized void onState(Input next) {
        if (next.equals(input)) return;
        boolean first = input == null;
        input = next;
        evaluate(first);
    }

    /** The configuration changed: evaluate again with the last reading, if there is one. */
    public synchronized void reevaluate() {
        if (input != null) evaluate(false);
    }

    private void evaluate(boolean first) {
        EnergyPolicy.Config config = EnergyPolicy.Config.fromSnapshot(snapshot, "1".equals(snapshot.apply("enabled")));
        Decision next = EnergyPolicy.decide(config, input, decision);
        boolean changed = !next.equals(decision);
        decision = next;
        if (next.suspendedMask != publishedMask) {
            publishedMask = next.suspendedMask;
            publisher.maskChanged(publishedMask);
        }
        if (changed || first) publisher.report(next, input);
    }
}

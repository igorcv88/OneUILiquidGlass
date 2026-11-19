package io.github.igorcv88.oneuiliquidglass;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.config.EnergyGovernor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** ENERGY_STATE reports are validated, then stored under engine_status energy.*; invalid ones change nothing. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class ConfigReadReceiverEnergyTest {
    private Context context;
    private SharedPreferences status;

    @Before public void clear() {
        context = RuntimeEnvironment.getApplication();
        status = context.getSharedPreferences("engine_status", Context.MODE_PRIVATE);
        status.edit().clear().commit();
    }

    private static Intent report(String reason, int mask, int level, boolean charging, long at) {
        return new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", reason).putExtra("mask", mask)
                .putExtra("level", level).putExtra("charging", charging).putExtra("at", at);
    }

    private void deliver(Intent intent) {
        new ConfigReadReceiver().onReceive(context, intent);
    }

    private void seedValid() {
        deliver(report("LOW_BATTERY", 17, 19, true, 1000L));
    }

    private void assertSeedUntouched() {
        assertEquals("LOW_BATTERY", status.getString("energy.reason", null));
        assertEquals(17, status.getInt("energy.mask", -1));
        assertEquals(19, status.getInt("energy.level", -1));
        assertTrue(status.getBoolean("energy.charging", false));
        assertEquals(1000L, status.getLong("energy.at", 0));
    }

    @Test public void validReportIsStored() {
        seedValid();
        assertSeedUntouched();
    }

    @Test public void everyReasonAndTheBoundariesAreAccepted() {
        for (String reason : new String[] {"NONE", "SAVER", "LOW_BATTERY"}) {
            deliver(report(reason, 127, 100, false, 5L));
            assertEquals(reason, status.getString("energy.reason", null));
        }
        deliver(report("NONE", 0, -1, false, 6L));
        assertEquals(0, status.getInt("energy.mask", -1));
        assertEquals(-1, status.getInt("energy.level", 0));
    }

    @Test public void invalidValuesAreIgnoredAndKeepTheLastReport() {
        seedValid();
        deliver(report("BOGUS", 1, 50, false, 2000L));
        deliver(report("low_battery", 1, 50, false, 2000L));
        deliver(report("NONE", 128, 50, false, 2000L));
        deliver(report("NONE", -1, 50, false, 2000L));
        deliver(report("NONE", 1, 101, false, 2000L));
        deliver(report("NONE", 1, -2, false, 2000L));
        deliver(report("NONE", 1, 50, false, 0L));
        deliver(report("NONE", 1, 50, false, -5L));
        assertSeedUntouched();
    }

    @Test public void missingOrWrongTypedExtrasAreIgnored() {
        seedValid();
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE));
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", "NONE"));
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", 3).putExtra("mask", 1)
                .putExtra("level", 5).putExtra("charging", false).putExtra("at", 9L));
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", "NONE").putExtra("mask", "1")
                .putExtra("level", 5).putExtra("charging", false).putExtra("at", 9L));
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", "NONE").putExtra("mask", 1)
                .putExtra("level", 5).putExtra("charging", "yes").putExtra("at", 9L));
        deliver(new Intent(EnergyGovernor.ACTION_ENERGY_STATE).putExtra("reason", "NONE").putExtra("mask", 1)
                .putExtra("level", 5).putExtra("charging", false).putExtra("at", "now"));
        assertSeedUntouched();
    }

    @Test public void otherActionsAreNotEnergyReports() {
        deliver(report("LOW_BATTERY", 1, 5, false, 7L).setAction("io.github.igorcv88.oneuiliquidglass.OTHER"));
        assertFalse(status.contains("energy.reason"));
    }

    @Test public void theConfigReadAcknowledgementStillWorks() {
        deliver(new Intent("io.github.igorcv88.oneuiliquidglass.CONFIG_READ").putExtra("generation", "4"));
        assertEquals(4L, status.getLong("readGeneration", -1));
    }
}

package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Decision;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Input;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;

/** The governor decides on a changed battery tuple or a changed config, publishes a mask, never writes saved flags. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class EnergyGovernorTest {
    private final List<String> events = new ArrayList<>();
    private final Map<String, String> snapshot = new HashMap<>();
    private final Function<String, String> lookup = snapshot::get;

    private final EnergyGovernor.Publisher publisher = new EnergyGovernor.Publisher() {
        @Override public void maskChanged(int mask) { events.add("mask=" + mask); }
        @Override public void report(Decision d, Input in) { events.add("report=" + d.reason + "/" + d.suspendedMask + "@" + in.level); }
    };

    private EnergyGovernor governor() {
        snapshot.put("enabled", "1");
        snapshot.put("energymode", "1");
        return new EnergyGovernor(lookup, publisher);
    }

    @After public void clearMask() { ConfigClient.energyMask = 0; }

    @Test public void firstStateIsReportedOnceEvenWhenNothingIsPaused() {
        EnergyGovernor g = governor();
        g.onState(new Input(80, true, false, false));
        assertEquals(Collections.singletonList("report=NONE/0@80"), events);
    }

    @Test public void repeatedTupleDoesNothing() {
        EnergyGovernor g = governor();
        g.onState(new Input(80, true, false, false));
        events.clear();
        g.onState(new Input(80, true, false, false));
        assertTrue(events.toString(), events.isEmpty());
    }

    @Test public void changedTupleWithTheSameDecisionPublishesNothing() {
        EnergyGovernor g = governor();
        g.onState(new Input(80, true, false, false));
        events.clear();
        g.onState(new Input(79, true, false, false));
        assertTrue(events.toString(), events.isEmpty());
    }

    @Test public void transitionPublishesOnceAndRepeatIsSilent() {
        EnergyGovernor g = governor();
        g.onState(new Input(80, true, false, false));
        events.clear();
        g.onState(new Input(20, true, false, false));
        assertEquals(Arrays.asList("mask=127", "report=LOW_BATTERY/127@20"), events);
        events.clear();
        g.onState(new Input(20, true, false, false));
        g.onState(new Input(22, true, false, false)); // hysteresis keeps it paused: same decision
        assertTrue(events.toString(), events.isEmpty());
        g.onState(new Input(25, true, false, false));
        assertEquals(Arrays.asList("mask=0", "report=NONE/0@25"), events);
    }

    @Test public void reasonChangeWithTheSameMaskReportsButDoesNotRepublishTheMask() {
        EnergyGovernor g = governor();
        snapshot.put("energysaver", "1");
        g.onState(new Input(10, true, false, false));
        events.clear();
        g.onState(new Input(10, true, false, true));
        assertEquals(Collections.singletonList("report=SAVER/127@10"), events);
    }

    @Test public void reevaluateAppliesTheNewConfigImmediately() {
        EnergyGovernor g = governor();
        g.onState(new Input(15, true, false, false));
        events.clear();
        snapshot.put("energymode", "0");
        g.reevaluate();
        assertEquals(Arrays.asList("mask=0", "report=NONE/0@15"), events);
        events.clear();
        snapshot.put("energymode", "1");
        snapshot.put("energysurfaces", "5");
        g.reevaluate();
        assertEquals(Arrays.asList("mask=5", "report=LOW_BATTERY/5@15"), events);
    }

    @Test public void reevaluateBeforeAnyBatteryReadingDoesNothing() {
        EnergyGovernor g = governor();
        g.reevaluate();
        assertTrue(events.toString(), events.isEmpty());
    }

    @Test public void masterOffReleasesEverything() {
        EnergyGovernor g = governor();
        g.onState(new Input(10, true, false, false));
        events.clear();
        snapshot.put("enabled", "0");
        g.reevaluate();
        assertEquals(Arrays.asList("mask=0", "report=NONE/0@10"), events);
    }

    @Test public void configIsClampedToTheRegistryDomains() {
        snapshot.put("energymode", "1");
        snapshot.put("energythreshold", "999");
        snapshot.put("energysurfaces", "0");
        snapshot.put("enabled", "1");
        EnergyGovernor g = new EnergyGovernor(lookup, publisher);
        g.onState(new Input(40, true, false, false));
        // surfaces clamps up to 1 (never zero), threshold down to 50: 40 <= 50 pauses the pop-up only.
        assertEquals(Arrays.asList("mask=1", "report=LOW_BATTERY/1@40"), events);
    }

    @Test public void parseReadsLevelPresenceAndPlug() {
        Intent b = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 19)
                .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PRESENT, true)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 2);
        assertEquals(new Input(19, true, true, true), EnergyGovernor.parse(b, true));
        Input scaled = EnergyGovernor.parse(
                new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 1).putExtra(BatteryManager.EXTRA_SCALE, 4), false);
        assertEquals(25, scaled.level);
    }

    @Test public void parseTreatsMissingOrBrokenDataAsUnknown() {
        assertEquals(new Input(-1, false, false, false), EnergyGovernor.parse(null, false));
        assertEquals(-1, EnergyGovernor.parse(new Intent(), false).level);
        assertEquals(-1, EnergyGovernor.parse(
                new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 5).putExtra(BatteryManager.EXTRA_SCALE, 0), false).level);
        assertFalse(EnergyGovernor.parse(new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 5)
                .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PRESENT, false), false).present);
    }

    @Test public void maskMapsBitsToTheSevenSurfaceFlags() {
        String[] flags = {"surface.headsup.enabled", "surface.keyguard.enabled", "surface.shade.enabled",
                ProductFlags.QUICK_SETTINGS, ProductFlags.LIVE_ACTIVITY, ProductFlags.NOW_BAR, ProductFlags.TOAST};
        for (int i = 0; i < flags.length; i++) {
            assertEquals(flags[i], 1 << i, EnergyGovernor.bitOf(flags[i]));
            for (int j = 0; j < flags.length; j++)
                assertEquals(i == j, EnergyGovernor.suspended(1 << i, flags[j]));
        }
        assertEquals(0, EnergyGovernor.bitOf("surface.shade.feature.glint.enabled"));
        assertFalse(EnergyGovernor.suspended(127, "surface.shade.feature.glint.enabled"));
    }

    @Test public void flagMasksOnlyTheSelectedBitsAndNeverTouchesTheSavedValue() throws Exception {
        Field f = ConfigClient.class.getDeclaredField("current");
        f.setAccessible(true);
        Object original = f.get(null);
        try {
            Map<String, String> saved = new HashMap<>();
            saved.put("surface.headsup.enabled", "1");
            saved.put("surface.shade.enabled", "1");
            saved.put(ProductFlags.TOAST, "1");
            f.set(null, Collections.unmodifiableMap(saved));
            assertTrue(ConfigClient.flag("surface.headsup.enabled"));
            ConfigClient.energyMask = 1 | 64;
            assertFalse(ConfigClient.flag("surface.headsup.enabled"));
            assertFalse(ConfigClient.flag(ProductFlags.TOAST));
            assertTrue(ConfigClient.flag("surface.shade.enabled"));
            assertEquals("the saved flag stays", "1", ConfigClient.override("surface.headsup.enabled"));
            ConfigClient.energyMask = 0;
            assertTrue(ConfigClient.flag("surface.headsup.enabled"));
        } finally {
            f.set(null, original);
        }
    }

    @Test public void startRegistersNotExportedReceiversAndReadsTheStickyBattery() {
        Context app = RuntimeEnvironment.getApplication();
        app.sendStickyBroadcast(new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 18)
                .putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_PRESENT, true));
        EnergyGovernor g = governor();
        g.start(app, new Handler(Looper.getMainLooper()));
        assertEquals(Arrays.asList("mask=127", "report=LOW_BATTERY/127@18"), events);
        boolean battery = false, saver = false;
        for (ShadowApplication.Wrapper w : Shadows.shadowOf((android.app.Application) app).getRegisteredReceivers()) {
            boolean b = w.getIntentFilter().hasAction(Intent.ACTION_BATTERY_CHANGED);
            boolean s = w.getIntentFilter().hasAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (!b && !s) continue;
            battery |= b;
            saver |= s;
            assertEquals(Context.RECEIVER_NOT_EXPORTED, w.flags & Context.RECEIVER_NOT_EXPORTED);
        }
        assertTrue("battery and saver registered", battery && saver);
    }
}

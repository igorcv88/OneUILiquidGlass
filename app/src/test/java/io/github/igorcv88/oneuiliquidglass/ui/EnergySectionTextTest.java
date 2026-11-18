package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.config.EnergyReport;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** What Settings > Energia and the Home notice say for each reported state. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class EnergySectionTextTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private static EnergyReport report(EnergyPolicy.Reason reason, int mask, int level, long at) {
        return new EnergyReport(reason, mask, level, false, at);
    }

    @Test public void activeGlass() {
        assertEquals("Now: glass active",
                EnergySection.statusText(context, report(EnergyPolicy.Reason.NONE, 0, 80, 1L), false, 20));
    }

    @Test public void pausedByLowBatteryNamesTheSurfacesLevelAndLimit() {
        String text = EnergySection.statusText(context, report(EnergyPolicy.Reason.LOW_BATTERY, 1 | 2, 19, 1L), false, 20);
        assertTrue(text, text.startsWith("Now: glass paused on "));
        assertTrue(text, text.contains("Pop-up") && text.contains("Lock"));
        assertTrue(text, text.contains("battery 19%") && text.contains("limit 20%"));
    }

    @Test public void pausedByBatterySaver() {
        assertEquals("Now: paused by Battery saver",
                EnergySection.statusText(context, report(EnergyPolicy.Reason.SAVER, 127, 80, 1L), false, 20));
    }

    @Test public void withoutAReportTheStateIsUnknown() {
        assertEquals("System state unknown",
                EnergySection.statusText(context, EnergyReport.unknown(), false, 20));
        assertEquals("System state unknown",
                EnergySection.statusText(context, report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 0L), false, 20));
    }

    @Test public void aSystemThatDoesNotAnswerIsUnknownEvenWithAStoredReport() {
        assertEquals("System state unknown",
                EnergySection.statusText(context, report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 5L), true, 20));
    }

    @Test public void reportedAtOnlyWhenKnown() {
        assertNull(EnergySection.reportedAt(context, EnergyReport.unknown(), false));
        assertNull(EnergySection.reportedAt(context, report(EnergyPolicy.Reason.NONE, 0, 80, 5L), true));
        assertTrue(EnergySection.reportedAt(context, report(EnergyPolicy.Reason.NONE, 0, 80, 5L), false)
                .startsWith("Reported at "));
    }

    @Test public void surfaceNamesFollowTheBitOrder() {
        assertEquals(7, EnergySection.surfaceNames(context, 127).size());
        assertEquals(java.util.Arrays.asList("Pop-up"), EnergySection.surfaceNames(context, 1));
        assertTrue(EnergySection.surfaceNames(context, 0).isEmpty());
    }

    @Test public void theSurfaceMaskNeverReachesZero() {
        assertEquals(3, EnergySection.toggled(1, 2, true));
        assertEquals(1, EnergySection.toggled(3, 2, false));
        assertEquals("the last surface cannot be switched off", 4, EnergySection.toggled(4, 4, false));
        assertEquals(127, EnergySection.toggled(127, 64, true));
    }

    @Test public void homeNoticeOnlyForAKnownPausedState() {
        assertTrue(EnergySection.homeNotice(report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 5L), AppModel.Sync.SYNCED));
        assertTrue(EnergySection.homeNotice(report(EnergyPolicy.Reason.SAVER, 1, 10, 5L), AppModel.Sync.SYNCED));
        assertFalse(EnergySection.homeNotice(report(EnergyPolicy.Reason.NONE, 0, 10, 5L), AppModel.Sync.SYNCED));
        assertFalse(EnergySection.homeNotice(report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 0L), AppModel.Sync.SYNCED));
        assertFalse(EnergySection.homeNotice(report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 5L), AppModel.Sync.NOT_DETECTED));
        assertFalse(EnergySection.homeNotice(report(EnergyPolicy.Reason.LOW_BATTERY, 1, 10, 5L), AppModel.Sync.OFF));
    }

    @Test public void reportRoundTripsThroughThePreferences() {
        SharedPreferences prefs = context.getSharedPreferences("engine_status_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        assertFalse(EnergyReport.read(prefs).known());
        EnergyReport r = new EnergyReport(EnergyPolicy.Reason.LOW_BATTERY, 17, 19, true, 1234L);
        r.write(prefs.edit()).commit();
        EnergyReport back = EnergyReport.read(prefs);
        assertEquals(EnergyPolicy.Reason.LOW_BATTERY, back.reason);
        assertEquals(17, back.mask);
        assertEquals(19, back.level);
        assertTrue(back.charging);
        assertEquals(1234L, back.at);
    }
}

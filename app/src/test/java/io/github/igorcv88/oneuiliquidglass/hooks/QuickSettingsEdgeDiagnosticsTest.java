package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class QuickSettingsEdgeDiagnosticsTest {
    @Test public void everyEffectiveGateHasItsOwnReason() {
        assertEquals("none", reason(true, true, true, true, true, 11, true));
        assertEquals("not-ready", reason(false, true, true, true, true, 11, true));
        assertEquals("option-off", reason(true, false, true, false, true, 11, true));
        assertEquals("master-off", reason(true, true, false, false, true, 11, true));
        assertEquals("compose-failed", reason(true, true, true, false, true, 11, true));
        assertEquals("sfrefract-off", reason(true, true, true, true, false, 11, true));
        assertEquals("optics-version", reason(true, true, true, true, true, 9, true));
        assertEquals("missing-host", reason(true, true, true, true, true, 11, false));
        assertEquals("none", reason(true, true, true, true, true, 10, true));
    }

    private String reason(boolean r, boolean o, boolean m, boolean e, boolean f, int v, boolean h) {
        return QuickSettingsEdgeDiagnostics.blocked(r, o, m, e, f, v, h);
    }

    @Test public void aStablePanelDoesNotProducePerFrameLogs() {
        List<String> logs = new ArrayList<>();
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(logs::add);
        assertEquals("opening", d.observe(true, .2f, 0, false));
        assertEquals("settled", d.observe(true, 1f, 0, false));
        // Reaching 95% without a drawn positive region must not claim visibility.
        for (int frame = 0; frame < 10000; frame++) {
            assertNull(d.observe(true, 1f, 0, false));
            d.gate(true, true, true, true, true, 11, true);
            if (d.count(QuickSettingsEdgeDiagnostics.Event.INVALID_RADIUS_TAG))
                d.event(QuickSettingsEdgeDiagnostics.Event.INVALID_RADIUS_TAG, "radius=20 eligible=false");
        }
        assertEquals(3, logs.size()); // session, gate, first geometry rejection
        assertEquals("region-visible", d.observe(true, 1f, 1, true));
        for (int frame = 0; frame < 10000; frame++) assertNull(d.observe(true, 1f, 1, false));
        assertEquals("exit", d.observe(true, 0f, 0, false));
        assertNull(d.observe(false, -1f, 0, false));
        assertEquals("opening", d.observe(true, .2f, 0, false));
        assertEquals("settled", d.observe(true, 1f, 1, false));
        assertEquals("region-visible", d.observe(true, 1f, 1, false));
        assertTrue(d.count(QuickSettingsEdgeDiagnostics.Event.INVALID_RADIUS_TAG));
    }

    @Test public void unreadableExpansionIsReportedWithoutInventingAnOpenFraction() {
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(s -> {});
        assertEquals("expansion-unreadable", d.observe(true, -1f, 0, false));
        for (int i = 0; i < 100; i++) assertNull(d.observe(true, -1f, 0, false));
        assertEquals("settled", d.observe(true, 1f, 0, false));
        assertNull(d.observe(true, 1f, 0, true));
        assertEquals("exit", d.observe(false, -1f, 0, false));
    }

    @Test public void togglingWhileOpenRearmsTheEvidenceWithoutNeedingARestart() {
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(s -> {});
        d.observe(true, 1f, 1, false);
        d.observe(true, 1f, 1, false);
        d.observe(true, 1f, 1, false);
        d.begin("off");
        assertEquals("settled", d.observe(true, 1f, 0, false));
        assertNull(d.observe(true, 1f, 0, false));
        d.begin("on");
        assertEquals("settled", d.observe(true, 1f, 0, false));
        assertEquals("region-visible", d.observe(true, 1f, 1, true));
    }

    @Test public void gateTransitionsAreVisibleEvenWhenBothVersionsSupportTheProfile() {
        List<String> logs = new ArrayList<>();
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(logs::add);
        d.gate(true, true, true, true, true, 10, true);
        d.gate(true, true, true, true, true, 10, true);
        d.gate(true, true, true, true, true, 11, true);
        assertEquals(2, logs.size());
        assertTrue(logs.get(1).contains("sfOptics=11"));
    }

    @Test public void alphaAnimationsOnlyLogWhenTheirBlockingCategoryChanges() {
        List<String> logs = new ArrayList<>();
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(logs::add);
        for (int frame = 1; frame <= 10000; frame++)
            d.fade(true, false, frame / 10000f, 1f, frame / 10000f);
        assertEquals(1, logs.size());
        d.fade(true, true, 1f, 1f, 0f);
        d.fade(true, false, 1f, -1f, 0f);
        assertEquals(3, logs.size());
        assertTrue(logs.get(2).contains("reason=expansion-unreadable"));
    }

    @Test public void summariesCountAllOccurrencesAndKeepPreOpeningComposition() {
        List<String> logs = new ArrayList<>();
        QuickSettingsEdgeDiagnostics d = new QuickSettingsEdgeDiagnostics(logs::add);
        d.count(QuickSettingsEdgeDiagnostics.Event.ELEMENT);
        d.count(QuickSettingsEdgeDiagnostics.Event.ELEMENT);
        d.observe(true, .1f, 0, false);
        d.count(QuickSettingsEdgeDiagnostics.Event.BIND);
        d.summary("exit", "pending=1");
        assertTrue(logs.get(logs.size() - 1).contains("specsCreated=2 specsBound=1"));
        d.count(QuickSettingsEdgeDiagnostics.Event.ELEMENT);
        d.begin("opening");
        d.summary("settled", "pending=1");
        assertTrue(logs.get(logs.size() - 1).contains("specsCreated=1 specsBound=0"));
    }
}

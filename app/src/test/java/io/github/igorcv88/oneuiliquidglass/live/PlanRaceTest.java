package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.fixtures.ActivationFixture;
import java.util.*;
import org.junit.Test;

/** What the person read is what runs: a change of the world changes the fingerprint or blocks the plan. */
public class PlanRaceTest {
    private static ConfirmedSnapshot snapshot(Map<String, String> observed, Map<String, String> status,
            boolean available, long revision) {
        return new ConfirmedSnapshot(ActivationFixture.desired(), observed,
                CompiledEvidence.of(ActivationFixture.checkpoint(), ActivationFixture.OLD_GENERATION, 1, status),
                status, true, available, false, revision);
    }

    private static PublicationPlan plan(ConfirmedSnapshot s) throws Exception {
        return PublicationPlan.of(s, Applicability::new);
    }

    @Test
    public void theSameWorldGivesTheSameFingerprint() throws Exception {
        String a = plan(ActivationFixture.snapshot()).fingerprint;
        String b = plan(ActivationFixture.snapshot()).fingerprint;
        assertEquals(a, b);
        assertTrue(a.matches("[0-9a-f]{40}"));
    }

    @Test
    public void aTerminalWriteBetweenPreviewAndTapChangesIt() throws Exception {
        Map<String, String> observed = new HashMap<>();
        observed.put("sf.hcsat", "1.4");
        assertNotEquals(plan(ActivationFixture.snapshot()).fingerprint,
                plan(snapshot(observed, ActivationFixture.statusNotLoaded(), true, 7)).fingerprint);
    }

    @Test
    public void aNewCompositorGenerationChangesIt() throws Exception {
        Map<String, String> status = new HashMap<>(ActivationFixture.statusNotLoaded());
        status.put("sf_generation", "g-newer");
        assertNotEquals(plan(ActivationFixture.snapshot()).fingerprint,
                plan(snapshot(Collections.<String, String>emptyMap(), status, true, 7)).fingerprint);
    }

    @Test
    public void aSavedChangeChangesIt() throws Exception {
        assertNotEquals(plan(ActivationFixture.snapshot()).fingerprint,
                plan(snapshot(Collections.<String, String>emptyMap(), ActivationFixture.statusNotLoaded(), true, 8)).fingerprint);
    }

    @Test
    public void unreadablePropertiesBlockThePlanAndWriteNothing() throws Exception {
        PublicationPlan p = plan(snapshot(Collections.<String, String>emptyMap(),
                ActivationFixture.statusNotLoaded(), false, 7));
        assertTrue(p.blocked);
        assertFalse(p.needsRestart());
        assertTrue(p.writes.isEmpty());
        assertNotEquals(plan(ActivationFixture.snapshot()).fingerprint, p.fingerprint);
    }
}

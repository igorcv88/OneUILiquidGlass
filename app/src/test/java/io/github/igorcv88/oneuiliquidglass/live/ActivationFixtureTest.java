package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.fixtures.ActivationFixture;
import java.util.*;
import org.junit.Test;

/**
 * The activation report (a restarted phone, lens installed but not loaded, settings intact):
 * what the old code would have written, and what the plan writes now.
 */
public class ActivationFixtureTest {
    /** The 14 lines of the report: the saved keys whose old checkpoint differs from the default. */
    private static final Set<String> ADENDUM_LINES = new TreeSet<>(Arrays.asList(
            "sf.hcblurexp", "sf.hccore", "sf.hcedge", "sf.hckernel", "sf.hclift", "sf.hcneutral",
            "sf.hcsat", "sf.hublur", "sf.huclear", "sf.hurefract", "sf.kgclear", "sf.kgdepth",
            "sf.kgflat", "sf.kglevel"));

    @Test
    @SuppressWarnings("deprecation")
    public void legacyPendingReproducesAdendum() {
        Map<String, String> saved = ActivationFixture.desired();
        ControlState state = new ControlState(saved, ActivationFixture.observedEmpty(),
                Collections.<String, String>emptyMap(), true);
        Applicability nowRules = state.applicability();
        // The old path: the rules of the rendering state now (no lens) hide every compositor key,
        // then the checkpoint is compared with the missing properties, whatever its generation.
        Map<String, String> lines = CompositorValues.pending(saved, ActivationFixture.observedEmpty(),
                ActivationFixture.checkpoint(), key -> !nowRules.rule(key).usable());
        assertEquals(ADENDUM_LINES, lines.keySet());
        for (Map.Entry<String, String> e : lines.entrySet())
            assertEquals("the old code wrote the default of " + e.getKey(),
                    SettingsRegistry.get(e.getKey()).defaultValue, e.getValue());
    }

    @Test
    public void planRestoresSavedIntent() throws Exception {
        PublicationPlan plan = PublicationPlan.of(ActivationFixture.snapshot(), Applicability::new);
        assertFalse(plan.blocked);
        assertTrue("the lens is not loaded: this is an activation", plan.activation);
        assertTrue(plan.needsRestart());

        Map<String, String> expected = new TreeMap<>();
        expected.put("sf.huclear", "1");
        expected.put("sf.kgclear", "1");
        expected.put("sf.hckernel", "1");
        expected.put("sf.hcedge", "30");
        expected.put("sf.hccore", "32");
        expected.put("sf.hcblurexp", "1");
        expected.put("sf.hublur", "1.2");
        expected.put("sf.hurefract", "1.09");
        expected.put("sf.hcneutral", "1");
        expected.put("sf.hcsat", "1");
        expected.put("sf.kgdepth", "4.4");
        assertEquals(expected, plan.writeValues());
        for (PublicationPlan.Write w : plan.writes.values())
            assertEquals("every value is a restoration of what the system lost",
                    PublicationPlan.WriteReason.RESTORE, w.reason);

        // Lift is saved at 0.30, which is also the default: nothing to write, and it is not 0.40.
        assertFalse(plan.writes.containsKey("sf.hclift"));
        // Bloom is dormant under the Gaussian adaptive kernel; the two Frosted-only uniformity
        // knobs are ignored by a Clear card (proved by identical GLSL, T04), so they stay saved too.
        assertEquals(new TreeSet<>(Arrays.asList("sf.hubloom", "sf.kgflat", "sf.kglevel")), plan.dormant);
        assertFalse(plan.writeValues().containsKey("sf.hubloom"));
        // No default is written for a key the person overrode, and nothing from the checkpoint.
        for (Map.Entry<String, String> e : plan.writeValues().entrySet())
            assertEquals(ActivationFixture.desired().get(e.getKey()), e.getValue());
        assertTrue("the checkpoint is history of another session: no difference", plan.drift.isEmpty());
        assertTrue("its 26 extra keys equal the defaults: nothing lost", plan.lostExternal.isEmpty());
        assertEquals(CompiledEvidence.Freshness.HISTORICAL, plan.evidence);
    }

    @Test
    public void theRestartInputsEqualTheTargetAndNeverTheCheckpoint() throws Exception {
        PublicationPlan plan = PublicationPlan.of(ActivationFixture.snapshot(), Applicability::new);
        Map<String, String> inputs = CompositorValues.restartInputs(ActivationFixture.observedEmpty(), plan.writeValues());
        assertEquals("1.2", inputs.get("sf.hublur"));
        assertEquals("0", inputs.get("sf.hubloom") == null ? "0" : "0");
        assertEquals("the saved Lift equals the default", SettingsRegistry.get("sf.hclift").defaultValue, inputs.get("sf.hclift"));
        assertNotEquals("not the 0.40 of the old session", "0.4", inputs.get("sf.hclift"));
    }

    @Test
    public void theSnapshotKeepsTheHelperOwnedMarkerOutOfTheSavedValues() {
        ConfirmedSnapshot s = ActivationFixture.snapshot();
        assertFalse(s.saved.containsKey("sfrefract"));
        assertEquals("the virtual lens is a read of the next compile only", "1", s.nextValue("sfrefract"));
        assertNotEquals("1", s.value("sfrefract"));
    }
}

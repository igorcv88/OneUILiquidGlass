package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.fixtures.ActivationFixture;
import java.util.*;
import org.junit.Test;

/** The next compile is judged with the lens loaded, whatever the system shows now. */
public class NextContextTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;

    @Test
    public void theNextContextHasTheVirtualLensTheNowContextLacks() {
        ConfirmedSnapshot s = ActivationFixture.snapshot();
        assertFalse(s.lensLoaded());
        assertEquals("1", s.nextValue("sfrefract"));
        assertNotEquals("1", s.value("sfrefract"));
    }

    @Test
    public void aCompositorKeyIsPendingActivationNotInactiveWhileTheLensIsOff() {
        ConfirmedSnapshot s = ActivationFixture.snapshot();
        Applicability now = new Applicability(s::value);
        Applicability next = new Applicability(s::nextValue);
        Applicability rules = now.withNext(next);
        assertFalse("not usable now", now.on("sf.hcsat", HU).usable());
        assertTrue("usable on the next compile", next.on("sf.hcsat", HU).usable());
        assertTrue(rules.pendingActivation("sf.hcsat", HU));
        assertTrue("the combined rules judge compositor keys on the next compile", rules.on("sf.hcsat", HU).usable());
    }

    @Test
    public void liveKeysAreStillJudgedOnTheStateNow() {
        ConfirmedSnapshot s = ActivationFixture.snapshot();
        Applicability rules = new Applicability(s::value).withNext(new Applicability(s::nextValue));
        assertFalse("a LIVE key never waits for an activation", rules.pendingActivation("liveradius", HU));
    }

    @Test
    public void aLoadedLensOnTheSameProcessIsNotAnActivation() throws Exception {
        Map<String, String> status = new HashMap<>(ActivationFixture.statusNotLoaded());
        status.put("sf_loaded", "1");
        status.put("sfrefract", "1");
        status.put("sf_rewrite", "1");
        status.put("sf_generation", ActivationFixture.OLD_GENERATION);
        Map<String, String> observed = new HashMap<>(ActivationFixture.checkpoint());
        observed.put("sfrefract", "1");
        ConfirmedSnapshot s = new ConfirmedSnapshot(ActivationFixture.desired(), observed,
                CompiledEvidence.of(ActivationFixture.checkpoint(), ActivationFixture.OLD_GENERATION, 1, status),
                status, true, true, false, 1);
        PublicationPlan plan = PublicationPlan.of(s, Applicability::new);
        assertFalse(plan.activation);
        assertEquals(CompiledEvidence.Freshness.CURRENT, plan.evidence);
        for (PublicationPlan.Write w : plan.writes.values())
            assertEquals("a loaded lens is never restoring a reset", PublicationPlan.WriteReason.CHANGE, w.reason);
    }
}

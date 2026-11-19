package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.fixtures.ActivationFixture;
import java.util.*;
import org.junit.Test;

/** Edits made before the activation join the restoration instead of replacing it. */
public class ActivationAfterEditTest {
    private static PublicationPlan plan(ConfirmedSnapshot s) throws Exception {
        return PublicationPlan.of(s, Applicability::new);
    }

    @Test
    public void anEditedValueIsWrittenAlongsideTheRestoredOnes() throws Exception {
        Map<String, String> saved = new TreeMap<>(ActivationFixture.desired());
        saved.put("sf.hclift", "0.4");
        PublicationPlan p = plan(ActivationFixture.snapshot().withSaved(saved, true));
        assertEquals("0.4", p.writeValues().get("sf.hclift"));
        assertEquals("1.2", p.writeValues().get("sf.hublur"));
        assertTrue(p.activation);
        assertEquals(12, p.writes.size());
    }

    @Test
    public void editingADormantValueKeepsItDormant() throws Exception {
        Map<String, String> saved = new TreeMap<>(ActivationFixture.desired());
        saved.put("sf.hubloom", "2");
        PublicationPlan p = plan(ActivationFixture.snapshot().withSaved(saved, true));
        assertTrue(p.dormant.contains("sf.hubloom"));
        assertFalse(p.writeValues().containsKey("sf.hubloom"));
    }

    @Test
    public void leavingTheGaussianKernelWakesTheDormantBloom() throws Exception {
        Map<String, String> saved = new TreeMap<>(ActivationFixture.desired());
        saved.put("sf.hckernel", "0");
        PublicationPlan p = plan(ActivationFixture.snapshot().withSaved(saved, true));
        assertFalse(p.dormant.contains("sf.hubloom"));
        assertEquals("1", p.writeValues().get("sf.hubloom"));
    }

    @Test
    public void theMasterSwitchDoesNotChangeWhatIsRestored() throws Exception {
        PublicationPlan on = plan(ActivationFixture.snapshot());
        PublicationPlan off = plan(ActivationFixture.snapshot().withSaved(ActivationFixture.desired(), false));
        assertEquals(on.writeValues(), off.writeValues());
        assertTrue(off.activation);
        assertFalse(off.blocked);
    }
}

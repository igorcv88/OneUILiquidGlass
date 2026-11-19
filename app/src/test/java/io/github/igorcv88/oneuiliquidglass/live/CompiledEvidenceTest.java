package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence.Freshness;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** A baseline is current only for the compositor process that compiled it. */
public class CompiledEvidenceTest {
    private static Map<String, String> status(String loaded, String refract, String rewrite, String generation) {
        Map<String, String> s = new HashMap<>();
        s.put("helper", "1");
        s.put("sf_loaded", loaded);
        s.put("sfrefract", refract);
        s.put("sf_rewrite", rewrite);
        s.put("sf_generation", generation);
        return s;
    }

    private static final Map<String, String> INPUTS = Collections.singletonMap("sf.hcsat", "1.2");

    @Test
    public void sameGenerationWithTheLensLoadedIsCurrent() {
        CompiledEvidence e = CompiledEvidence.of(INPUTS, "g1", 5, status("1", "1", "1", "g1"));
        assertEquals(Freshness.CURRENT, e.freshness);
        assertTrue(e.current());
        assertTrue(e.has());
    }

    @Test
    public void anotherGenerationIsHistorical() {
        CompiledEvidence e = CompiledEvidence.of(INPUTS, "g1", 5, status("1", "1", "1", "g2"));
        assertEquals(Freshness.HISTORICAL, e.freshness);
        assertFalse(e.current());
        assertTrue(e.has());
    }

    @Test
    public void anUnloadedLensIsHistoricalEvenWithEqualGenerations() {
        assertEquals(Freshness.HISTORICAL,
                CompiledEvidence.of(INPUTS, "g1", 5, status("0", "1", "1", "g1")).freshness);
        assertEquals(Freshness.HISTORICAL,
                CompiledEvidence.of(INPUTS, "g1", 5, status("1", "", "1", "g1")).freshness);
        assertEquals(Freshness.HISTORICAL,
                CompiledEvidence.of(INPUTS, "g1", 5, status("1", "1", "0", "g1")).freshness);
    }

    @Test
    public void noRecordedCompileIsNone() {
        assertEquals(Freshness.NONE, CompiledEvidence.none().freshness);
        assertFalse(CompiledEvidence.none().has());
        assertEquals(Freshness.NONE,
                CompiledEvidence.of(null, "", 0, status("1", "1", "1", "g1")).freshness);
    }

    @Test
    public void aCompileOfNothingButDefaultsIsStillACompile() {
        assertTrue(CompiledEvidence.of(Collections.<String, String>emptyMap(), "g1", 5, status("1", "1", "1", "g1")).current());
    }

    @Test
    public void aMissingStatusNeverMakesABaselineCurrent() {
        assertEquals(Freshness.HISTORICAL,
                CompiledEvidence.of(INPUTS, "g1", 5, Collections.<String, String>emptyMap()).freshness);
    }
}

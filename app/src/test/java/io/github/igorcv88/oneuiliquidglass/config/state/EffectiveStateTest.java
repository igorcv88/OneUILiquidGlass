package io.github.igorcv88.oneuiliquidglass.config.state;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
import java.util.*;
import org.junit.Test;

/** One test per branch of the effective-state algorithm (contract section 6). */
public class EffectiveStateTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, String> plus(Map<String, String> base, String... kv) {
        Map<String, String> m = new HashMap<>(base);
        m.putAll(map(kv));
        return m;
    }

    private static final Map<String, String> LENS = map("sfrefract", "1");
    private static final Map<String, String> CLEAR = plus(LENS, "sf.huclear", "1");
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;

    private static EffectiveState.Effective of(ControlState st, Map<String, String> compiled, String key) throws Exception {
        return EffectiveState.of(st, PublicationPlan.of(st.snapshot(compiled, false),
                io.github.igorcv88.oneuiliquidglass.config.Applicability::new), key, HU);
    }

    private static ControlState state(Map<String, String> saved, Map<String, String> observed) {
        return new ControlState(saved, observed, Collections.emptyMap(), true);
    }

    @Test
    public void readOnlyKeysShowTheConfirmedValue() throws Exception {
        EffectiveState.Effective e = of(state(Collections.emptyMap(), LENS), CLEAR, "sfrefract");
        assertEquals(EffectiveState.Kind.READ_ONLY, e.kind);
        assertEquals("1", e.displayValue);
    }

    @Test
    public void offShowsOffAndKeepsTheSavedValueAsLatent() throws Exception {
        ControlState st = state(map("sf.hclift", "0.4"), CLEAR).withEnabled(false);
        EffectiveState.Effective e = of(st, CLEAR, "sf.hclift");
        assertEquals(EffectiveState.Kind.OFF, e.kind);
        assertEquals(EffectiveState.DISPLAY_OFF, e.displayValue);
        assertEquals("0.4", e.latentValue);
        assertFalse(e.reason.isEmpty());
    }

    @Test
    public void inactiveShowsInactiveWithTheSavedValueAsLatent() throws Exception {
        Map<String, String> o = plus(CLEAR, "sf.hckernel", "1");
        EffectiveState.Effective e = of(state(map("sf.hubloom", "1"), o), o, "sf.hubloom");
        assertEquals(EffectiveState.Kind.INACTIVE, e.kind);
        assertEquals(EffectiveState.DISPLAY_INACTIVE, e.displayValue);
        assertEquals("1", e.latentValue);
        assertFalse(e.reason.isEmpty());
        assertNull(of(state(Collections.emptyMap(), o), o, "sf.hubloom").latentValue);
    }

    @Test
    public void aWriteIsPendingAndShowsTheSavedValue() throws Exception {
        EffectiveState.Effective e = of(state(map("sf.hclift", "0.4"), CLEAR), CLEAR, "sf.hclift");
        assertEquals(EffectiveState.Kind.PENDING_COMPILE, e.kind);
        assertEquals("0.4", e.displayValue);
        assertTrue(e.notes.contains(EffectiveState.Note.APPLY_TO_TAKE_EFFECT));
    }

    @Test
    public void externalDriftIsPendingWithTheObservedValueAndAnOutsideNote() throws Exception {
        Map<String, String> compiled = plus(CLEAR, "sf.hcsat", "1.05");
        EffectiveState.Effective e = of(state(Collections.emptyMap(), plus(CLEAR, "sf.hcsat", "1.2")), compiled, "sf.hcsat");
        assertEquals(EffectiveState.Kind.PENDING_COMPILE, e.kind);
        assertEquals("1.2", e.displayValue);
        assertTrue(e.notes.contains(EffectiveState.Note.APPLY_TO_TAKE_EFFECT));
        assertTrue(e.notes.contains(EffectiveState.Note.CHANGED_OUTSIDE_APP));
    }

    @Test
    public void anExternalValueAlreadyCompiledIsExternal() throws Exception {
        Map<String, String> o = plus(CLEAR, "sf.hcsat", "1.2");
        EffectiveState.Effective e = of(state(Collections.emptyMap(), o), o, "sf.hcsat");
        assertEquals(EffectiveState.Kind.EXTERNAL, e.kind);
        assertEquals("1.2", e.displayValue);
    }

    @Test
    public void aCompositorKeyWithoutABaselineIsUnknown() throws Exception {
        EffectiveState.Effective e = of(state(Collections.emptyMap(), CLEAR), null, "sf.hcband");
        assertEquals(EffectiveState.Kind.UNKNOWN, e.kind);
        assertTrue(e.notes.contains(EffectiveState.Note.UNKNOWN_COMPILED));
        assertEquals("0.42", e.displayValue);
    }

    @Test
    public void anOrdinaryKeyIsActiveAndShowsTheConfirmedValue() throws Exception {
        EffectiveState.Effective e = of(state(map("liveradius", "40"), Collections.emptyMap()), null, "liveradius");
        assertEquals(EffectiveState.Kind.ACTIVE, e.kind);
        assertEquals("40", e.displayValue);
        assertTrue(e.notes.isEmpty());
        assertNull(e.latentValue);
    }

    @Test
    public void aDraftChangesTheDisplayOnlyWhileActive() throws Exception {
        ControlState st = state(map("liveradius", "40"), Collections.emptyMap());
        PublicationPlan plan = PublicationPlan.of(st.snapshot(null, false),
                io.github.igorcv88.oneuiliquidglass.config.Applicability::new);
        assertEquals("55", EffectiveState.of(st, plan, "liveradius", HU, "55").displayValue);
        assertEquals("40", EffectiveState.of(st, plan, "liveradius", HU, null).displayValue);

        Map<String, String> o = plus(CLEAR, "sf.hckernel", "1");
        ControlState inactive = state(map("sf.hubloom", "1"), o);
        PublicationPlan p2 = PublicationPlan.of(inactive.snapshot(o, false),
                io.github.igorcv88.oneuiliquidglass.config.Applicability::new);
        assertEquals(EffectiveState.DISPLAY_INACTIVE, EffectiveState.of(inactive, p2, "sf.hubloom", HU, "2").displayValue);
    }

    @Test
    public void theRuleUsesTheConfirmedValuesNotTheDrafts() throws Exception {
        ControlState st = state(Collections.emptyMap(), CLEAR).withDraft("sf.hckernel", "1");
        Map<String, String> compiled = plus(CLEAR, "sf.hubloom", "0");
        // hubloom stays usable: the kernel is still the original one until the draft is saved.
        assertNotEquals(EffectiveState.Kind.INACTIVE, of(st, compiled, "sf.hubloom").kind);
    }
}

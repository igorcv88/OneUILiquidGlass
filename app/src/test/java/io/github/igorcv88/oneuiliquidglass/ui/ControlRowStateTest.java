package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;
import io.github.igorcv88.oneuiliquidglass.config.state.Snapshots;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The text and the accessibility description of a row for every effective state. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class ControlRowStateTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;
    private static final Map<String, String> CLEAR = map("sfrefract", "1", "sf.huclear", "1");

    private final Context c = RuntimeEnvironment.getApplication();

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

    private static EffectiveState.Effective effective(
            Map<String, String> saved, Map<String, String> observed, Map<String, String> compiled, boolean enabled,
            String key, boolean intent) throws Exception {
        ControlState st = new ControlState(saved, observed, Collections.emptyMap(), enabled);
        PublicationPlan plan = PublicationPlan.of(Snapshots.of(st, compiled, false), Applicability::new);
        return EffectiveState.of(st, plan, key, HU, null, intent);
    }

    @Test
    public void anActiveRowShowsTheValueAndNoStatusLine() throws Exception {
        RowText t = RowText.of(c, effective(map("liveradius", "40"), map(), null, true, "liveradius", false), "Blur", "40 px");
        assertEquals("40 px", t.value);
        assertNull(t.line);
        assertEquals("Blur, 40 px", t.description);
    }

    @Test
    public void aPendingRowSaysApplyToTakeEffectInText() throws Exception {
        RowText t = RowText.of(c, effective(map("sf.hclift", "0.4"), CLEAR, CLEAR, true, "sf.hclift", false), "Brightening", "0.40");
        assertEquals(c.getString(R.string.state_pending), t.line);
        assertEquals(RowText.Tone.PENDING, t.tone);
        assertEquals("Brightening, 0.40, " + c.getString(R.string.state_pending), t.description);
    }

    @Test
    public void anInactiveRowShowsInactiveInsteadOfANumberWithTheReason() throws Exception {
        Map<String, String> o = plus(CLEAR, "sf.hckernel", "1");
        EffectiveState.Effective e = effective(map("sf.hubloom", "1"), o, o, true, "sf.hubloom", false);
        RowText t = RowText.of(c, e, "Bloom containment", "1.00");
        assertEquals(c.getString(R.string.state_inactive), t.value);
        assertFalse("the reason is the second line", t.line == null || t.line.isEmpty());
        assertTrue(t.description.contains(c.getString(R.string.state_inactive)));
        assertTrue(t.description.contains(t.line));
        assertFalse(RowText.enabled(e));
    }

    @Test
    public void aRowWithTheGlassOffShowsOff() throws Exception {
        EffectiveState.Effective e = effective(map("sf.hclift", "0.4"), CLEAR, CLEAR, false, "sf.hclift", false);
        RowText t = RowText.of(c, e, "Brightening", "0.40");
        assertEquals(c.getString(R.string.state_off), t.value);
        assertFalse(RowText.enabled(e));
    }

    @Test
    public void aValueSetOutsideTheAppIsLabelledAndNotOnlyColoured() throws Exception {
        Map<String, String> o = plus(CLEAR, "sf.hcsat", "1.2");
        RowText t = RowText.of(c, effective(map(), o, o, true, "sf.hcsat", false), "Saturation", "1.20");
        assertEquals(c.getString(R.string.state_external), t.line);
        assertEquals(RowText.Tone.EXTERNAL, t.tone);
        assertTrue(t.description.endsWith(c.getString(R.string.state_external)));
    }

    @Test
    public void externalDriftThatNeedsAnApplyNamesBoth() throws Exception {
        Map<String, String> compiled = plus(CLEAR, "sf.hcsat", "1.05");
        RowText t = RowText.of(c, effective(map(), plus(CLEAR, "sf.hcsat", "1.2"), compiled, true, "sf.hcsat", false),
                "Saturation", "1.20");
        assertTrue(t.line.contains(c.getString(R.string.state_pending)));
        assertTrue(t.line.contains(c.getString(R.string.state_external)));
    }

    @Test
    public void anUnloadedLensWithIntentSaysItAppliesOnActivation() throws Exception {
        EffectiveState.Effective e = effective(map("sf.huclear", "1", "sf.hcsat", "1.2"), map(), null, true, "sf.hcsat", true);
        RowText t = RowText.of(c, e, "Saturation", "1.20");
        assertEquals(c.getString(R.string.state_pending_activation), t.line);
        assertTrue(RowText.enabled(e));
    }

    @Test
    public void anUnknownCompileIsSaidSo() throws Exception {
        RowText t = RowText.of(c, effective(map(), CLEAR, null, true, "sf.hcband", false), "Band", "0.42");
        assertEquals(c.getString(R.string.state_unknown_compiled), t.line);
    }

    @Test
    public void aNullPlanLeavesTheRulesAsTheOnlyInformation() {
        ControlState st = new ControlState(map("liveradius", "40"), map(), Collections.emptyMap(), true);
        EffectiveState.Effective e = EffectiveState.of(st, null, "liveradius", HU, null, false);
        assertEquals(EffectiveState.Kind.ACTIVE, e.kind);
    }
}

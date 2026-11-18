package io.github.igorcv88.oneuiliquidglass.config.state;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import java.util.*;
import org.junit.Test;

/** The confirmed snapshot never sees a draft and resolves values the way the readers do. */
public class ConfirmedSnapshotTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static ControlState state(Map<String, String> saved, Map<String, String> observed, Map<String, String> drafts) {
        return new ControlState(saved, observed, drafts, true);
    }

    @Test
    public void aDraftDoesNotChangeTheConfirmedValueButChangesTheShownOne() {
        ControlState st = state(map("liveradius", "40"), Collections.emptyMap(), map("liveradius", "99", "sf.hcfold", "2"));
        assertEquals("99", st.shown("liveradius"));
        assertEquals("40", st.confirmed("liveradius"));
        assertEquals("40", st.snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1).value("liveradius"));
        // A draft of a key with nothing saved falls back to the device value, never the draft.
        assertEquals(SettingsRegistry.get("sf.hcfold").defaultValue, st.confirmed("sf.hcfold"));
        assertEquals("2", st.shown("sf.hcfold"));
    }

    @Test
    public void applicabilityIgnoresDraftsAndThePreviewRuleFollowsThem() {
        // Clear material drafted but not saved: the confirmed rule still sees Frosted.
        ControlState st = state(Collections.emptyMap(), map("sfrefract", "1"), map("sf.huclear", "1"));
        assertEquals(io.github.igorcv88.oneuiliquidglass.config.Applicability.Kind.NEEDS_RESTART, st.applicability().rule("sf.core").kind);
        assertEquals(io.github.igorcv88.oneuiliquidglass.config.Applicability.Kind.INACTIVE, st.previewApplicability().rule("sf.core").kind);
    }

    @Test
    public void helperOwnedAndReadOnlyKeysReadTheObservedValue() {
        ControlState st = state(map("sfrefract", "0"), map("sfrefract", "1"), Collections.emptyMap());
        ConfirmedSnapshot s = st.snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("1", s.value("sfrefract"));
        assertEquals("1", st.confirmed("sfrefract"));
        SettingSpec ro = null;
        for (SettingSpec spec : SettingsRegistry.all()) if (spec.type == SettingSpec.Type.READONLY) { ro = spec; break; }
        assertNotNull(ro);
        assertEquals(ro.defaultValue, s.value(ro.key));
        ConfirmedSnapshot seen = state(Collections.emptyMap(), map(ro.key, "7"), Collections.emptyMap()).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("7", seen.value(ro.key));
    }

    @Test
    public void aFlagWithoutASavedValueUsesItsProductDefault() {
        ConfirmedSnapshot s = state(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("1", s.value("surface.headsup.enabled"));
        assertEquals("0", s.value(ProductFlags.QUICK_SETTINGS));
        ConfirmedSnapshot on = state(map(ProductFlags.QUICK_SETTINGS, "1"), Collections.emptyMap(), Collections.emptyMap()).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("1", on.value(ProductFlags.QUICK_SETTINGS));
        assertEquals("1", s.value("enabled"));
        assertEquals("0", state(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()).withEnabled(false).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1).value("enabled"));
    }

    @Test
    public void aKeyWithoutASavedValueUsesTheObservedValueThenTheDefault() {
        ConfirmedSnapshot s = state(Collections.emptyMap(), map("sf.hcsat", "1.2", "sf.hckernel", ""), Collections.emptyMap()).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("1.2", s.value("sf.hcsat"));
        assertEquals(SettingsRegistry.get("sf.hckernel").defaultValue, s.value("sf.hckernel"));
        assertEquals(SettingsRegistry.get("sf.hcband").defaultValue, s.value("sf.hcband"));
        assertNull(s.value("not.a.key"));
    }

    @Test
    public void savedWinsOverObservedForTheAppsIntent() {
        ConfirmedSnapshot s = state(map("sf.hcsat", "1.4"), map("sf.hcsat", "1.2"), Collections.emptyMap()).snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        assertEquals("1.4", s.value("sf.hcsat"));
    }

    @Test
    public void everyRegistryKeyResolvesLikeControlStateConfirmed() {
        Map<String, String> saved = new HashMap<>(), observed = new HashMap<>();
        int i = 0;
        for (SettingSpec spec : SettingsRegistry.all()) {
            if (i % 3 == 0) saved.put(spec.key, spec.defaultValue);
            if (i % 4 == 0) observed.put(spec.key, i % 8 == 0 ? "" : spec.defaultValue);
            i++;
        }
        ControlState st = state(saved, observed, map("liveradius", "5"));
        ConfirmedSnapshot s = st.snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1);
        for (SettingSpec spec : SettingsRegistry.all())
            assertEquals(spec.key, st.confirmed(spec.key), s.value(spec.key));
    }

    @Test
    public void theSnapshotIsImmutableAndKeepsAnUnknownBaselineApart() {
        Map<String, String> compiled = map("sf.core", "48");
        ControlState st = state(map("sf.core", "50"), Collections.emptyMap(), Collections.emptyMap());
        ConfirmedSnapshot s = Snapshots.of(st, compiled, true);
        compiled.put("sf.core", "99");
        assertEquals("48", s.compiled.inputs.get("sf.core"));
        assertTrue(s.awaitingVerification);
        assertEquals(CompiledEvidence.Freshness.NONE,
                st.snapshot(CompiledEvidence.none(), Collections.<String, String>emptyMap(), false, 1).compiled.freshness);
        try {
            s.saved.put("x", "y");
            fail("saved must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}

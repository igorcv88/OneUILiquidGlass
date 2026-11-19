package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.config.state.Snapshots;
import io.github.igorcv88.oneuiliquidglass.live.PlanContradiction;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
import java.util.*;
import org.junit.Test;

/**
 * A key that an import, a style or Undo removes goes back to its default, as the review says: the
 * transaction saves the default explicitly so the next Apply publishes it. Handing a key to the
 * Terminal is a different, explicit action ({@code ConfigRepository.releaseToExternal}).
 */
public class ImportRemovalRestoresDefaultTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final String SAT_DEFAULT = SettingsRegistry.get("sf.hcsat").defaultValue;

    /** sf.hcsat saved, set and compiled at 1.30 in the loaded lens, pop-up in Clear. */
    private static ConfirmedSnapshot published() {
        Map<String, String> sys = map("sfrefract", "1", "sf.huclear", "1", "sf.hcsat", "1.30");
        return Snapshots.of(map("sf.huclear", "1", "sf.hcsat", "1.30"), sys, sys, true, false);
    }

    private static PresetCodec.Parsed backupWithoutSat() {
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.values.put("sf.huclear", "1");
        parsed.enabled = true;
        return parsed;
    }

    private static ImportPlan.Change removed(ImportPlan plan, String key) {
        for (ImportPlan.Change c : plan.removed) if (c.key.equals(key)) return c;
        return null;
    }

    private static void assertNextApplyPublishesDefault(ConfirmedSnapshot now, ImportPlan plan)
            throws PlanContradiction {
        ImportPlan.Change c = removed(plan, "sf.hcsat");
        assertNotNull("the review lists the removal", c);
        assertEquals("1.30", c.from);
        assertEquals("the review promises the default", SAT_DEFAULT, c.to);
        assertEquals("the transaction saves what the review promised", SAT_DEFAULT, plan.result.get("sf.hcsat"));

        PublicationPlan next = PublicationPlan.of(now.withSaved(plan.result, plan.enabled), Applicability::new);
        PublicationPlan.Write w = next.writes.get("sf.hcsat");
        assertNotNull("the next Apply publishes the default", w);
        assertEquals(SAT_DEFAULT, w.value);
        assertEquals(PublicationPlan.WriteReason.CHANGE, w.reason);
        assertFalse("the old property is not handed to the Terminal", next.external.contains("sf.hcsat"));
        assertTrue(plan.needsLens);
    }

    @Test
    public void replaceImportWithoutTheKeyPublishesTheDefault() throws Exception {
        ConfirmedSnapshot now = published();
        assertNextApplyPublishesDefault(now,
                ImportPlan.of(now, backupWithoutSat(), ImportPlan.Mode.REPLACE, false, false));
    }

    @Test
    public void undoToASnapshotWithoutTheKeyPublishesTheDefault() throws Exception {
        ConfirmedSnapshot now = published();
        assertNextApplyPublishesDefault(now, ImportPlan.ofSnapshot(now, map("sf.huclear", "1"), true));
    }

    @Test
    public void styleAppliedInReplacePublishesTheDefault() throws Exception {
        ConfirmedSnapshot now = published();
        PresetCodec.Parsed style = backupWithoutSat();
        assertNextApplyPublishesDefault(now, ImportPlan.of(now, style, ImportPlan.Mode.REPLACE, false, false));
    }

    /**
     * Why the default must be saved: merely dropping the override (what the transaction did before)
     * leaves the published 1.30 in force and turns it into a Terminal value. That is the explicit
     * "use the Terminal value" action, not a restore.
     */
    @Test
    public void droppingTheOverrideReleasesTheKeyInsteadOfRestoringIt() throws Exception {
        ConfirmedSnapshot dropped = published().withSaved(map("sf.huclear", "1"), true);
        PublicationPlan next = PublicationPlan.of(dropped, Applicability::new);
        assertFalse(next.writes.containsKey("sf.hcsat"));
        assertTrue(next.external.contains("sf.hcsat"));
    }

    @Test
    public void mergeNeverRemoves() {
        ConfirmedSnapshot now = published();
        ImportPlan plan = ImportPlan.of(now, backupWithoutSat(), ImportPlan.Mode.MERGE, false, false);
        assertTrue(plan.removed.isEmpty());
        assertEquals("1.30", plan.result.get("sf.hcsat"));
    }

    @Test
    public void aRemovedValueAlreadyAtTheDefaultIsKeptNotRemoved() {
        Map<String, String> sys = map("sfrefract", "1");
        ConfirmedSnapshot now = Snapshots.of(map("sf.hcsat", SAT_DEFAULT), sys, sys, true, false);
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.enabled = true;
        ImportPlan plan = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertNull(removed(plan, "sf.hcsat"));
        assertTrue(plan.kept.contains("sf.hcsat"));
        assertTrue(plan.noChanges());
    }

    /** Java (LIVE) parameters fall back to the debug.oulg property when nothing is saved. */
    @Test
    public void aRemovedJavaParameterIsSavedAsItsDefault() {
        SettingSpec live = null;
        for (SettingSpec s : SettingsRegistry.all())
            if (s.applyMode == SettingSpec.ApplyMode.LIVE && !s.helperOwned()
                    && s.type == SettingSpec.Type.FLOAT && !s.key.startsWith("energy")) { live = s; break; }
        assertNotNull(live);
        String other = live.validate(String.valueOf(
                Double.parseDouble(live.defaultValue) == live.max ? live.min : live.max));
        Map<String, String> sys = map(live.key, other);
        ConfirmedSnapshot now = Snapshots.of(map(live.key, other), sys, null, true, false);
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.enabled = true;
        ImportPlan plan = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertEquals(live.defaultValue, removed(plan, live.key).to);
        assertEquals("the Terminal property no longer wins", live.defaultValue, plan.result.get(live.key));
    }

    @Test
    public void aRemovedSurfaceFlagIsSavedAsItsDefault() {
        String flag = ProductFlags.QUICK_SETTINGS;
        String def = ProductFlags.defaultEnabled(flag) ? "1" : "0";
        String other = "1".equals(def) ? "0" : "1";
        Map<String, String> sys = map("sfrefract", "1");
        ConfirmedSnapshot now = Snapshots.of(map(flag, other), sys, sys, true, false);
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.enabled = true;
        ImportPlan plan = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertEquals(def, plan.result.get(flag));
        assertEquals(def, removed(plan, flag).to);
    }
}

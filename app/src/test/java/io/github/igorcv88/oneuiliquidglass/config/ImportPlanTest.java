package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.config.state.Snapshots;
import java.util.*;
import org.junit.Test;

/** The review and the apply read the same plan, in Replace and Merge. */
public class ImportPlanTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static ConfirmedSnapshot current(Map<String, String> saved) {
        return Snapshots.of(saved, map("sfrefract", "1"), map("sfrefract", "1"), true, false);
    }

    private static PresetCodec.Parsed file(Map<String, String> values) {
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.values.putAll(values);
        parsed.enabled = true;
        return parsed;
    }

    private static Set<String> keys(List<ImportPlan.Change> changes) {
        Set<String> out = new TreeSet<>();
        for (ImportPlan.Change c : changes) out.add(c.key);
        return out;
    }

    @Test
    public void replaceDropsWhatTheFileDoesNotHaveAndMergeKeepsIt() {
        ConfirmedSnapshot now = current(map("sf.hcsat", "1.2", "sf.hclift", "0.4"));
        PresetCodec.Parsed parsed = file(map("sf.hcsat", "1.3"));
        ImportPlan replace = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertEquals(map("sf.hcsat", "1.3"), replace.result);
        assertEquals(Collections.singleton("sf.hcsat"), keys(replace.changed));
        assertEquals(Collections.singleton("sf.hclift"), keys(replace.removed));

        ImportPlan merge = ImportPlan.of(now, parsed, ImportPlan.Mode.MERGE, false, false);
        assertEquals(map("sf.hcsat", "1.3", "sf.hclift", "0.4"), merge.result);
        assertTrue(merge.removed.isEmpty());
        assertTrue(merge.kept.contains("sf.hclift"));
    }

    @Test
    public void aNewKeyIsAddedAndAnIdenticalOneIsNotAChange() {
        ConfirmedSnapshot now = current(map("sf.hcsat", "1.2"));
        ImportPlan plan = ImportPlan.of(now, file(map("sf.hcsat", "1.2", "sf.hclift", "0.4")),
                ImportPlan.Mode.REPLACE, false, false);
        assertEquals(Collections.singleton("sf.hclift"), keys(plan.added));
        assertTrue(plan.changed.isEmpty());
    }

    @Test
    public void anIdenticalFileHasNoChanges() {
        ConfirmedSnapshot now = current(map("sf.hcsat", "1.2"));
        assertTrue(ImportPlan.of(now, file(map("sf.hcsat", "1.2")), ImportPlan.Mode.REPLACE, false, false).noChanges());
    }

    @Test
    public void backupDefaultsAreAppliedOnlyOnRequestAndOnlyWhereTheyDiffer() {
        PresetCodec.Parsed parsed = file(map());
        SettingSpec lift = SettingsRegistry.get("sf.hclift");
        parsed.implicitDefaults.put("sf.hclift", "0.55");
        parsed.implicitDefaults.put("sf.hcsat", SettingsRegistry.get("sf.hcsat").defaultValue);
        ConfirmedSnapshot now = current(map());
        assertNotEquals("0.55", lift.defaultValue);

        ImportPlan off = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertTrue(off.hasBackupDefaults);
        assertTrue(off.result.isEmpty());

        ImportPlan on = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, true, false);
        assertEquals(map("sf.hclift", "0.55"), on.result);
    }

    @Test
    public void terminalValuesAreAdoptedOnlyByChoiceAndNeverOverTheFilesOwnKeys() {
        PresetCodec.Parsed parsed = file(map("sf.hcsat", "1.2"));
        parsed.terminal.put("sf.hcsat", "1.9");
        parsed.terminal.put("sf.hclift", "0.4");
        ConfirmedSnapshot now = current(map());

        ImportPlan off = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertTrue(off.hasTerminal);
        assertEquals(map("sf.hcsat", "1.2"), off.result);

        ImportPlan on = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, true);
        assertEquals(map("sf.hcsat", "1.2", "sf.hclift", "0.4"), on.result);
    }

    @Test
    public void theEnabledFlagIsPartOfTheReview() {
        ConfirmedSnapshot now = current(map());
        PresetCodec.Parsed parsed = file(map());
        parsed.enabled = false;
        ImportPlan plan = ImportPlan.of(now, parsed, ImportPlan.Mode.REPLACE, false, false);
        assertTrue(plan.enabledChanges);
        assertFalse(plan.enabled);
        assertFalse(plan.noChanges());
    }

    @Test
    public void aSavedValueTheNextCompileCannotUseIsListedAsDormant() {
        PresetCodec.Parsed parsed = file(map("sf.hubloom", "1", "sf.hckernel", "1", "sf.huclear", "1"));
        ImportPlan plan = ImportPlan.of(current(map()), parsed, ImportPlan.Mode.REPLACE, false, false);
        assertTrue(plan.dormant.toString(), plan.dormant.contains("sf.hubloom"));
    }

    @Test
    public void undoAsAPlanIsAReplaceOfTheSnapshot() {
        ConfirmedSnapshot now = current(map("sf.hcsat", "1.5", "sf.hclift", "0.4"));
        ImportPlan plan = ImportPlan.ofSnapshot(now, map("sf.hcsat", "1.2"), true);
        assertEquals(map("sf.hcsat", "1.2"), plan.result);
        assertEquals(Collections.singleton("sf.hclift"), keys(plan.removed));
        assertEquals(ImportPlan.Mode.REPLACE, plan.mode);
    }

    @Test
    public void thePlanDoesNotMutateWhatIsSavedNow() {
        Map<String, String> saved = map("sf.hcsat", "1.2");
        ConfirmedSnapshot now = current(saved);
        ImportPlan.of(now, file(map("sf.hclift", "0.4")), ImportPlan.Mode.MERGE, false, false);
        assertEquals(map("sf.hcsat", "1.2"), saved);
        assertEquals(map("sf.hcsat", "1.2"), now.saved);
    }
}

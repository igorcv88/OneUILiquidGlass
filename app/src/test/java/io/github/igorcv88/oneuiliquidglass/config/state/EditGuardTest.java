package io.github.igorcv88.oneuiliquidglass.config.state;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard.Action;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard.Outcome;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;

/** Every row of the action table (contract section 7), and the rule that drafts never reach the rules. */
public class EditGuardTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;

    private static Applicability rules(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Applicability(k -> {
            if (m.containsKey(k)) return m.get(k);
            io.github.igorcv88.oneuiliquidglass.config.SettingSpec spec =
                    io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry.get(k);
            return spec == null ? null : spec.defaultValue;
        });
    }

    /** Standard mode: liveradius is read (ACTIVE), sf.core needs the lens (INACTIVE). */
    private static final Applicability STANDARD = rules();
    private static final Applicability LENS = rules("sfrefract", "1");
    private static final Applicability OFF = rules("enabled", "0");

    private static Outcome check(Action a, String key, Applicability r) {
        return EditGuard.check(a, key, HU, r).outcome;
    }

    @Test
    public void editAndResetOneAllowOnlyUsableKeys() {
        for (Action a : new Action[] {Action.EDIT, Action.RESET_ONE}) {
            assertEquals(a.name(), Outcome.ALLOW, check(a, "liveradius", STANDARD));
            assertEquals(a.name(), Outcome.ALLOW, check(a, "sf.core", LENS));
            assertEquals(a.name(), Outcome.REFUSE, check(a, "sf.core", STANDARD));
            assertEquals(a.name(), Outcome.REFUSE, check(a, "liveradius", OFF));
            assertEquals(a.name(), Outcome.REFUSE, check(a, "sfrefract", LENS));
        }
        EditGuard.Decision refused = EditGuard.check(Action.EDIT, "sf.core", HU, STANDARD);
        assertFalse(refused.permitted());
        assertFalse("a refusal carries the reason", refused.reason.isEmpty());
    }

    @Test
    public void resetGroupSkipsWhatIsNotUsable() {
        assertEquals(Outcome.ALLOW, check(Action.RESET_GROUP, "liveradius", STANDARD));
        assertEquals(Outcome.SKIP, check(Action.RESET_GROUP, "sf.core", STANDARD));
        assertEquals(Outcome.SKIP, check(Action.RESET_GROUP, "liveradius", OFF));
        assertEquals(Outcome.SKIP, check(Action.RESET_GROUP, "sfrefract", LENS));
    }

    @Test
    public void favoritesAcceptInactiveAndOffButNotReadOnly() {
        assertEquals(Outcome.ALLOW, check(Action.FAVORITE_ADD, "liveradius", STANDARD));
        assertEquals(Outcome.ALLOW_LATENT, check(Action.FAVORITE_ADD, "sf.core", STANDARD));
        assertEquals(Outcome.ALLOW_LATENT, check(Action.FAVORITE_ADD, "liveradius", OFF));
        assertEquals(Outcome.REFUSE, check(Action.FAVORITE_ADD, "sfrefract", LENS));
        assertTrue(EditGuard.check(Action.FAVORITE_ADD, "sf.core", HU, STANDARD).permitted());
    }

    @Test
    public void theEditorOpensReadOnlyInsteadOfRefusing() {
        assertEquals(Outcome.ALLOW, check(Action.OPEN_EDITOR, "liveradius", STANDARD));
        for (String key : new String[] {"sf.core", "sfrefract"})
            assertEquals(key, Outcome.VIEW_ONLY, check(Action.OPEN_EDITOR, key, key.equals("sf.core") ? STANDARD : LENS));
        assertEquals(Outcome.VIEW_ONLY, check(Action.OPEN_EDITOR, "liveradius", OFF));
        assertTrue(EditGuard.check(Action.OPEN_EDITOR, "sf.core", HU, STANDARD).permitted());
    }

    @Test
    public void importsAndPresetsKeepInactiveValuesAsLatentAndIgnoreReadOnly() {
        for (Action a : new Action[] {Action.BULK_IMPORT, Action.PRESET_APPLY}) {
            assertEquals(a.name(), Outcome.ALLOW, check(a, "liveradius", STANDARD));
            assertEquals(a.name(), Outcome.ALLOW_LATENT, check(a, "sf.core", STANDARD));
            assertEquals(a.name(), Outcome.ALLOW_LATENT, check(a, "liveradius", OFF));
            assertEquals(a.name(), Outcome.SKIP, check(a, "sfrefract", LENS));
        }
    }

    @Test
    public void materialSelectionIsAlwaysAllowed() {
        for (Applicability r : new Applicability[] {STANDARD, LENS, OFF})
            assertEquals(Outcome.ALLOW, EditGuard.check(Action.MATERIAL_SELECT, null, null, r).outcome);
    }

    @Test
    public void aGuardWithoutASurfaceUsesWhereverTheKeyApplies() {
        assertEquals(Outcome.ALLOW, EditGuard.check(Action.EDIT, "liveradius", null, STANDARD).outcome);
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Architecture rule: drafts are a preview. The files that decide editing, publication and the
     * rows must not call previewApplicability (only the preview itself may).
     */
    @Test
    public void noDraftsInRules() throws Exception {
        String root = "src/main/java/io/github/igorcv88/oneuiliquidglass/";
        List<File> files = new ArrayList<>(Arrays.asList(
                new File(root + "ui/AppModel.java"),
                new File(root + "live/PublicationPlan.java"),
                new File(root + "ui/SurfacesPage.java")));
        File[] state = new File(root + "config/state").listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull(state);
        files.addAll(Arrays.asList(state));
        assertTrue(files.size() > 5);
        for (File f : files) {
            assertTrue(f + " exists", f.isFile());
            String text = source(f.getPath());
            assertFalse(f.getName() + " calls previewApplicability(", text.contains("previewApplicability("));
        }
        // The publication and guard layers read confirmed values only.
        for (String name : new String[] {"live/PublicationPlan.java", "config/state/EditGuard.java",
                "config/state/EffectiveState.java", "config/state/ConfirmedSnapshot.java"}) {
            String text = source(root + name);
            assertFalse(name + " reads shown()", text.contains(".shown("));
            assertFalse(name + " reads drafts", text.contains(".drafts"));
        }
    }
}

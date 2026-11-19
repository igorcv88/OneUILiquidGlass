package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.config.ConfigRepository.Source;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Each saved value remembers the last operation that wrote it; Undo names the operation it takes back. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class ProvenanceTest {
    private SharedPreferences prefs;
    private ConfigRepository repo;

    @Before
    public void open() {
        Context context = RuntimeEnvironment.getApplication();
        prefs = Config.open(context);
        prefs.edit().clear().commit();
        repo = new ConfigRepository(context);
    }

    @After
    public void clean() {
        prefs.edit().clear().commit();
    }

    private static SettingSpec spec(String key) {
        return SettingsRegistry.get(key);
    }

    @Test
    public void anEditIsRecordedWithItsTime() {
        assertNull(repo.provenance("sf.hcsat"));
        repo.save(spec("sf.hcsat"), "1.2");
        ConfigRepository.Provenance p = repo.provenance("sf.hcsat");
        assertEquals(Source.EDIT, p.source);
        assertTrue(p.at > 0);
    }

    @Test
    public void theLastOperationWins() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.save(spec("sf.hcsat"), "1.3", Source.MATERIAL);
        assertEquals(Source.MATERIAL, repo.provenance("sf.hcsat").source);
    }

    @Test
    public void aBulkReplaceMarksEveryKeyItWrites() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sf.hcsat", "1.2");
        result.put("sf.hclift", "0.4");
        repo.replaceAll(result, null, Source.IMPORT);
        assertEquals(Source.IMPORT, repo.provenance("sf.hcsat").source);
        assertEquals(Source.IMPORT, repo.provenance("sf.hclift").source);
    }

    @Test
    public void aBulkReplaceLeavesKeysItDoesNotMentionAlone() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.save(spec("sf.hcfold"), "2");
        Map<String, String> result = new LinkedHashMap<>(repo.overrides());
        result.put("sf.hclift", "0.4");
        repo.replaceAll(result, null, Source.STYLE);
        assertEquals(Source.STYLE, repo.provenance("sf.hclift").source);
        repo.save(spec("sf.hcsat"), "1.3");
        assertEquals("a later edit is its own operation", Source.EDIT, repo.provenance("sf.hcsat").source);
        assertEquals(Source.STYLE, repo.provenance("sf.hclift").source);
    }

    @Test
    public void releasingAKeyForgetsItsProvenance() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.releaseToExternal(spec("sf.hcsat"));
        assertNull(repo.provenance("sf.hcsat"));
    }

    @Test
    public void undoNamesTheOperationItTakesBack() {
        repo.save(spec("sf.hcsat"), "1.2");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sf.hclift", "0.4");
        repo.replaceAll(result, null, Source.IMPORT);
        ConfigRepository.UndoSnapshot snapshot = repo.undoSnapshot();
        assertNotNull(snapshot);
        assertEquals(Source.IMPORT, snapshot.label);
        assertEquals("1.2", snapshot.overrides.get("sf.hcsat"));
        assertTrue(snapshot.at > 0);
    }

    @Test
    public void undoRestoresTheSnapshotAndMarksItAsUndo() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.save(spec("sf.hcsat"), "1.5");
        assertTrue(repo.undo());
        assertEquals("1.2", repo.overrides().get("sf.hcsat"));
        assertEquals(Source.UNDO, repo.provenance("sf.hcsat").source);
    }

    @Test
    public void aSnapshotWithoutALabelIsStillReadable() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.save(spec("sf.hcsat"), "1.5");
        prefs.edit().remove("undo.label").commit();
        ConfigRepository.UndoSnapshot snapshot = repo.undoSnapshot();
        assertNotNull(snapshot);
        assertNull(snapshot.label);
    }

    @Test
    public void clearingTheStoreDropsTheProvenanceToo() {
        repo.save(spec("sf.hcsat"), "1.2");
        repo.replace(Collections.<String, String>emptyMap(), true);
        assertNull(repo.provenance("sf.hcsat"));
        for (String key : prefs.getAll().keySet()) assertFalse(key, key.startsWith("src.") || key.startsWith("srcAt."));
    }
}

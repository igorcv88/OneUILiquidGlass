package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.igorcv88.oneuiliquidglass.Config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class IconStagingTest {
    @Test public void iconEditsDoNotReachSystemUiUntilApply() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(app);
        prefs.edit().clear().putString("desired.statusiconsize", "0.88").commit();
        try {
            ConfigRepository repo = new ConfigRepository(app);
            long initialGeneration = repo.generation();
            repo.save(SettingsRegistry.get("statusiconsize"), "0.73");
            repo.save(SettingsRegistry.get("iconcardsize"), "0.81");
            assertEquals(initialGeneration, repo.generation());
            assertEquals("0.88", repo.snapshotValues().overrides.get("statusiconsize"));
            assertFalse(repo.snapshotValues().overrides.containsKey("iconcardsize"));
            assertEquals("0.73", repo.overrides().get("statusiconsize"));
            assertEquals(2, repo.pendingIcons().size());

            // Editing again collapses into one queued change, and reverting cancels it.
            repo.save(SettingsRegistry.get("statusiconsize"), "0.88");
            assertEquals(1, repo.pendingIcons().size());
            repo.save(SettingsRegistry.get("statusiconsize"), "0.79");
            repo.applyIcons();
            assertTrue(repo.pendingIcons().isEmpty());
            assertEquals(initialGeneration + 1, repo.generation());
            assertEquals("0.79", repo.snapshotValues().overrides.get("statusiconsize"));
            assertEquals("0.81", repo.snapshotValues().overrides.get("iconcardsize"));
        } finally { prefs.edit().clear().commit(); }
    }

    @Test public void otherControlsPublishNormallyWhileIconEditsRemainStaged() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(app);
        prefs.edit().clear().commit();
        try {
            ConfigRepository repo = new ConfigRepository(app);
            repo.save(SettingsRegistry.get("statusmaxicons"), "6");
            Map<String, String> both = new LinkedHashMap<>();
            both.put("statusiconsize", "0.93");
            both.put("mediaart", "0.42");
            repo.replace(both, false);
            assertEquals("0.42", repo.snapshotValues().overrides.get("mediaart"));
            assertFalse(repo.snapshotValues().overrides.containsKey("statusiconsize"));
            assertFalse(repo.snapshotValues().overrides.containsKey("statusmaxicons"));
            assertEquals(2, repo.pendingIcons().size());
        } finally { prefs.edit().clear().commit(); }
    }

    @Test public void pendingIconChangesSurviveRepositoryRecreationAndUndo() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(app);
        prefs.edit().clear().putString("desired.statusiconsize", "0.88").commit();
        try {
            ConfigRepository repo = new ConfigRepository(app);
            repo.save(SettingsRegistry.get("statusiconsize"), "0.75");
            assertEquals("0.75", new ConfigRepository(app).pendingIcons().get("statusiconsize"));
            assertTrue(repo.undo());
            assertTrue(repo.pendingIcons().isEmpty());
            assertEquals("0.88", repo.snapshotValues().overrides.get("statusiconsize"));
        } finally { prefs.edit().clear().commit(); }
    }
}

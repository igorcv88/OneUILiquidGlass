package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class DefaultsPersistenceTest {
    @Test public void explicitMediaAndOtherOverridesSurviveOpeningRepository() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(context);
        prefs.edit().clear().putString("desired.mediaart", "0.25")
                .putString("desired.kgdarkfill", "14").putString("desired.qs.lightcolor", "12345678").commit();
        try {
            ConfigRepository repo = new ConfigRepository(context);
            assertEquals("0.25", repo.desired(SettingsRegistry.get("mediaart")));
            assertEquals("14", repo.desired(SettingsRegistry.get("kgdarkfill")));
            assertEquals("12345678", repo.desired(SettingsRegistry.get("qs.lightcolor")));
            assertEquals("0.25", repo.snapshotValues().overrides.get("mediaart"));
            assertEquals("14", prefs.getString("desired.kgdarkfill", null));
        } finally { prefs.edit().clear().commit(); }
    }

    @Test public void emptyStateUsesNewDefaultsAndRejectedPresetDoesNotWrite() throws Exception {
        ControlState state = new ControlState(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), true);
        assertEquals("0.50", state.shown("mediaart"));
        assertEquals("5", state.shown("kgdarkfill"));
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(context);
        prefs.edit().clear().putString("desired.mediaart", "0.45").commit();
        try {
            ConfigRepository repo = new ConfigRepository(context);
            try {
                PresetCodec.parse("{\"schemaVersion\":1,\"enabled\":true,\"desired\":{\"mediaart\":\"0.9\",\"kgdarkfill\":\"bad\"}}");
                fail("invalid preset accepted");
            } catch (PresetCodec.InvalidPreset expected) { assertEquals(PresetCodec.Error.VALUE, expected.error); }
            assertEquals("0.45", repo.snapshotValues().overrides.get("mediaart"));
            assertFalse(repo.snapshotValues().overrides.containsKey("kgdarkfill"));
        } finally { prefs.edit().clear().commit(); }
    }
}

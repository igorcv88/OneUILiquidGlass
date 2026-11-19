package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.ReaderState;
import io.github.igorcv88.oneuiliquidglass.config.state.LensIntent;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * The userspace restart that reloads the lens goes through the same plan as the Apply dialog:
 * after a reboot the properties are gone, so a restart that writes nothing would compile defaults.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class AppModelReinjectTest {
    private Context context;
    private SharedPreferences config;
    private ActivityController<MainActivity> controller;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        config = Config.open(context);
        config.edit().clear().putBoolean(Config.KEY_ENABLED, true).commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void cleanUp() {
        if (controller != null) controller.pause().stop().destroy();
        ReaderState.set(Collections.emptyMap(), false);
        config.edit().clear().commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private AppModel open(boolean readable) {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        ReaderState.set(Collections.emptyMap(), readable);
        return controller.get().model();
    }

    @Test
    public void unreadablePropertiesBlockTheRestartInsteadOfWritingNothing() {
        AppModel m = open(false);
        AppModel.Reinject r = m.reinjectPlan();
        assertEquals(AppModel.Reinject.Outcome.BLOCKED, r.outcome);
        assertTrue(r.writes.isEmpty());
    }

    @Test
    public void afterARebootTheSavedIntentIsWrittenBackNotLeftToCompileDefaults() {
        AppModel m = open(true);
        assertTrue(m.selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(m.save("sf.hclift", "0.4").ok);
        LensIntent.record(m.lensPrefs(), true);
        m.notifyChanged();
        AppModel.Reinject r = m.reinjectPlan();
        assertEquals(AppModel.Reinject.Outcome.START, r.outcome);
        assertEquals("1", r.writes.get(GlassMaterial.HU_SELECTOR));
        assertEquals("0.4", r.writes.get("sf.hclift"));
        PublicationPlan plan = m.plan();
        assertEquals(plan.fingerprint, r.fingerprint);
        assertEquals(plan.writeValues(), r.writes);
    }

    @Test
    public void withNothingSavedItStartsWithNoWritesAndTheCurrentFingerprint() {
        AppModel m = open(true);
        AppModel.Reinject r = m.reinjectPlan();
        assertEquals(AppModel.Reinject.Outcome.START, r.outcome);
        assertTrue(r.writes.isEmpty());
        assertEquals(m.plan().fingerprint, r.fingerprint);
    }

    @Test
    public void theReturnedWritesAreACopyTheCallerCannotUseToChangeThePlan() {
        AppModel m = open(true);
        assertTrue(m.save("sf.hcsat", "1.2").ok);
        LensIntent.record(m.lensPrefs(), true);
        m.notifyChanged();
        AppModel.Reinject r = m.reinjectPlan();
        try {
            r.writes.put("sf.hclift", "1");
            fail("immutable");
        } catch (UnsupportedOperationException expected) {
            // immutable
        }
    }
}

package io.github.igorcv88.oneuiliquidglass.config.state;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The person's intent to use the lens survives the reboot that unloads it. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class LensIntentTest {
    @Test
    public void aLoadedLensIsAlwaysIntent() {
        assertTrue(LensIntent.of(true, false, false));
        assertTrue(LensIntent.of(true, null, false));
    }

    @Test
    public void aStoredChoiceWinsOverHistory() {
        assertTrue(LensIntent.of(false, true, false));
        assertFalse("removed on purpose", LensIntent.of(false, false, true));
    }

    @Test
    public void anUpgradeDerivesIntentFromCompileHistory() {
        assertTrue(LensIntent.of(false, null, true));
        assertFalse("a fresh install has no intent", LensIntent.of(false, null, false));
    }

    @Test
    public void recordPersistsAcrossReopening() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("lens_intent_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        LensIntent.record(prefs, true);
        assertTrue(context.getSharedPreferences("lens_intent_test", Context.MODE_PRIVATE)
                .getBoolean(LensIntent.PREF, false));
        LensIntent.record(prefs, false);
        assertFalse(prefs.getBoolean(LensIntent.PREF, true));
    }
}

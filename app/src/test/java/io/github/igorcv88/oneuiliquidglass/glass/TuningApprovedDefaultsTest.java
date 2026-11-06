package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TuningApprovedDefaultsTest {
    @Test public void kgSharedOpticsAloneInvalidatesTheCachedTuningSignature() throws Exception {
        Field current = ConfigClient.class.getDeclaredField("current"); current.setAccessible(true);
        Constructor<Tuning> constructor = Tuning.class.getDeclaredConstructor(); constructor.setAccessible(true);
        Object old = current.get(null);
        try {
            current.set(null, Collections.emptyMap());
            Tuning baseline = constructor.newInstance();
            assertFalse(baseline.kgSharedOptics);
            current.set(null, Collections.singletonMap("kgsharedoptics", "1"));
            Tuning experiment = constructor.newInstance();
            assertTrue(experiment.kgSharedOptics);
            assertNotEquals(baseline.toString(), experiment.toString());
            assertTrue(experiment.toString().contains("kgsharedoptics=true"));
        } finally { current.set(null, old); }
    }

    @Test public void runtimeFallbacksAndExplicitOverridesAreIndependent() throws Exception {
        Field current = ConfigClient.class.getDeclaredField("current"); current.setAccessible(true);
        Constructor<Tuning> constructor = Tuning.class.getDeclaredConstructor(); constructor.setAccessible(true);
        Object old = current.get(null);
        try {
            current.set(null, Collections.emptyMap());
            Tuning t = constructor.newInstance();
            assertEquals(60, t.liveShadeRadius); assertEquals(.22f, t.shadeGlint, 0f);
            assertEquals(0, t.semRadius); assertEquals(14, t.semRadiusLens);
            assertEquals(.55f, t.sfLens, 0f); assertEquals(0, t.semAlpha);
            assertEquals(12, t.kgBlurRadius); assertEquals(5, t.kgDarkFill);
            assertFalse(t.kgSharedOptics);
            assertEquals(.18f, t.shadeLightGlass, 0f); assertEquals(0, t.liveDarkFill);
            assertFalse(t.headerTone); assertEquals(.08f, t.panelDim, 0f);
            assertEquals(70, t.panelBlurCap); assertEquals(7f, t.headerGap, 0f);
            assertEquals(.50f, t.mediaArt, 0f); assertEquals(.25f, t.qsMediaArt, 0f);
            Map<String, String> saved = new HashMap<>();
            saved.put("mediaart", "0.25"); saved.put("kgdarkfill", "14");
            saved.put("kgsharedoptics", "1");
            current.set(null, saved);
            t = constructor.newInstance();
            assertEquals(.25f, t.mediaArt, 0f); assertEquals(14, t.kgDarkFill);
            assertTrue(t.kgSharedOptics);
            assertEquals(.25f, t.qsMediaArt, 0f);
        } finally { current.set(null, old); }
    }
}

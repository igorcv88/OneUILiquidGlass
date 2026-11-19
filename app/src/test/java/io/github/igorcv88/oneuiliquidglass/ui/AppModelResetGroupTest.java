package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.ReaderState;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/** Restoring a group is one transaction (one undo) and never touches a value that is not in effect. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class AppModelResetGroupTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;
    private Context context;
    private SharedPreferences config;
    private ActivityController<MainActivity> controller;
    private Map<String, String> observed;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        config = Config.open(context);
        config.edit().clear().putBoolean(Config.KEY_ENABLED, true).commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
        observed = Collections.singletonMap("sfrefract", "1");
        ReaderState.set(observed, true);
    }

    @After
    public void cleanUp() {
        if (controller != null) controller.pause().stop().destroy();
        ReaderState.set(Collections.emptyMap(), false);
        config.edit().clear().commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private AppModel open() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        ReaderState.set(observed, true);
        return controller.get().model();
    }

    private static double saved(AppModel m, String key) {
        return Double.parseDouble(m.state().saved.get(key));
    }

    private static double def(String key) {
        return Double.parseDouble(SettingsRegistry.get(key).defaultValue);
    }

    @Test
    public void parentAndChildrenReturnToDefaultInOneUndoStep() {
        AppModel m = open();
        assertTrue(m.selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(m.save("sf.hublur", "1.4").ok);
        assertTrue(m.save("sf.hcedge", "30").ok);
        assertTrue(m.save("sf.hccore", "32").ok);
        List<String> refused = m.resetGroup(Arrays.asList("sf.hublur", "sf.hcedge", "sf.hccore"), HU);
        assertTrue(refused.isEmpty());
        assertEquals(def("sf.hublur"), saved(m, "sf.hublur"), 1e-9);
        assertEquals(def("sf.hcedge"), saved(m, "sf.hcedge"), 1e-9);
        assertEquals(def("sf.hccore"), saved(m, "sf.hccore"), 1e-9);
        assertTrue(m.undo());
        assertEquals("one undo restores all three", 1.4, saved(m, "sf.hublur"), 1e-9);
        assertEquals(30, saved(m, "sf.hcedge"), 1e-9);
        assertEquals(32, saved(m, "sf.hccore"), 1e-9);
    }

    @Test
    public void anInactiveKeyKeepsItsLatentValueAndIsReportedBack() {
        AppModel m = open();
        assertTrue(m.selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(m.save("sf.hubloom", "1").ok);
        assertTrue(m.save("sf.hckernel", "1").ok);
        assertTrue(m.save("sf.hclift", "0.4").ok);
        List<String> refused = m.resetGroup(Arrays.asList("sf.hubloom", "sf.hclift"), HU);
        assertEquals(Collections.singletonList("sf.hubloom"), refused);
        assertEquals("1", m.state().saved.get("sf.hubloom"));
        assertEquals(def("sf.hclift"), saved(m, "sf.hclift"), 1e-9);
    }

    @Test
    public void aDraftOnAKeyDoesNotUnlockItForTheGroupReset() {
        AppModel m = open();
        assertTrue(m.selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(m.save("sf.hubloom", "1").ok);
        assertTrue(m.save("sf.hckernel", "1").ok);
        m.draft("sf.hckernel", "0");
        assertEquals(Collections.singletonList("sf.hubloom"), m.resetGroup(Collections.singletonList("sf.hubloom"), HU));
        assertEquals("1", m.state().saved.get("sf.hubloom"));
    }
}

package io.github.igorcv88.oneuiliquidglass.shots;

import android.view.View;
import app.cash.paparazzi.DeviceConfig;
import app.cash.paparazzi.Paparazzi;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import java.util.*;

/** Sheets, pending, error and drag states. */
public class StateShots {
    @Rule public Paparazzi paparazzi = new Paparazzi(app.cash.paparazzi.EnvironmentKt.detectEnvironment(), DeviceConfig.PIXEL_6_PRO);

    @After public void reset() { ShotHost.observe(Collections.emptyMap()); }

    private View sheet(ShotHost h, View screen, Sheet s) {
        return h.overlay(screen, s.panel());
    }

    @Test public void sliderEditor() {
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        View screen = h.screen(1, Controls.Surface.HEADSUP, 0, 0);
        paparazzi.snapshot(sheet(h, screen, Shots.control(h, "liverim", Controls.Surface.HEADSUP)), "sheet_slider");
    }

    @Test public void choiceEditor() {
        ShotHost h = new ShotHost(paparazzi.getContext(), false);
        View screen = h.screen(1, Controls.Surface.SHADE, 0, 0);
        paparazzi.snapshot(sheet(h, screen, Shots.control(h, "shadetone", Controls.Surface.SHADE)), "sheet_choice");
    }

    @Test public void behaviourEditor() {
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        View screen = h.screen(1, Controls.Surface.HEADSUP, 0, 0);
        paparazzi.snapshot(sheet(h, screen, Shots.control(h, "lenssettle", Controls.Surface.HEADSUP)), "sheet_no_preview");
    }

    @Test public void testOptions() {
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        View screen = h.screen(0, null, 0, 0);
        paparazzi.snapshot(sheet(h, screen, Shots.tests(h)), "sheet_tests");
    }

    @Test public void dragWithMagnifier() {
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        View screen = h.screen(1, Controls.Surface.HEADSUP, 0, 0);
        h.preview("liverim", "4", Controls.Channel.RIM);
        paparazzi.snapshot(screen, "drag_rim");
    }

    @Test public void lensPending() {
        Map<String, String> observed = new HashMap<>();
        observed.put("sfrefract", "1");
        observed.put("sf.optics", "9");
        ShotHost.observe(observed);
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        h.model.repo.save(SettingsRegistry.get("sf.kgcore"), "96");
        h.model.repo.save(SettingsRegistry.get("sf.kgsat"), "1.4");
        paparazzi.snapshot(h.screen(0, null, 0, 0), "home_pending");
        ShotHost h2 = new ShotHost(paparazzi.getContext(), true);
        h2.model.repo.save(SettingsRegistry.get("sf.kgcore"), "96");
        paparazzi.snapshot(h2.screen(1, Controls.Surface.KEYGUARD, 0, 0), "lock_lens_mode");
    }

    @Test public void lensSheet() {
        Map<String, String> observed = new HashMap<>();
        observed.put("sfrefract", "1");
        ShotHost.observe(observed);
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        h.model.repo.save(SettingsRegistry.get("sf.kgcore"), "96");
        View screen = h.screen(2, null, 0, 0);
        paparazzi.snapshot(sheet(h, screen, Shots.lens(h)), "sheet_lens");
    }

    @Test public void noResponse() {
        ShotHost h = new ShotHost(paparazzi.getContext(), true);
        h.activity.getSharedPreferences("engine_status", 0).edit().putLong("readGeneration", 0).apply();
        h.model.repo.preferences().edit().putLong("generation", 5).putLong("lastChange", 1).apply();
        paparazzi.snapshot(h.screen(0, null, 0, 0), "home_no_response");
    }

    @Test public void inactiveRowsInLensMode() {
        ShotHost.observe(Collections.singletonMap("sfrefract", "1"));
        ShotHost h = new ShotHost(paparazzi.getContext(), false);
        paparazzi.snapshot(h.screen(1, Controls.Surface.HEADSUP, 0, 0), "popup_lens_light");
    }
}

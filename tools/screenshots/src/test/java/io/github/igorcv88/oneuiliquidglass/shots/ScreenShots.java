package io.github.igorcv88.oneuiliquidglass.shots;

import app.cash.paparazzi.DeviceConfig;
import app.cash.paparazzi.Paparazzi;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import org.junit.Rule;
import org.junit.Test;

/** Full screens at a Galaxy S25 Ultra-like size (1440 x 3120, 19.5:9). */
public class ScreenShots {
    @Rule public Paparazzi paparazzi = new Paparazzi(app.cash.paparazzi.EnvironmentKt.detectEnvironment(), DeviceConfig.PIXEL_6_PRO);

    private void shot(String name, boolean dark, int tab, Controls.Surface s) {
        ShotHost host = new ShotHost(paparazzi.getContext(), dark);
        paparazzi.snapshot(host.screen(tab, s, 0, 0), name);
    }

    @Test public void homeDark() { shot("home_dark", true, 0, null); }
    @Test public void homeLight() { shot("home_light", false, 0, null); }
    @Test public void popupDark() { shot("popup_dark", true, 1, Controls.Surface.HEADSUP); }
    @Test public void lockDark() { shot("lock_dark", true, 1, Controls.Surface.KEYGUARD); }
    @Test public void shadeLight() { shot("shade_light", false, 1, Controls.Surface.SHADE); }
    @Test public void settingsDark() { shot("settings_dark", true, 2, null); }
}

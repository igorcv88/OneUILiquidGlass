package io.github.igorcv88.oneuiliquidglass.shots;

import app.cash.paparazzi.Paparazzi;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import org.junit.Rule;
import org.junit.Test;

public class AdaptiveShots {
    @Rule public Paparazzi paparazzi = new Paparazzi(app.cash.paparazzi.EnvironmentKt.detectEnvironment(), Devices.largeFont());

    @Test public void largeFontHome() {
        paparazzi.snapshot(new ShotHost(paparazzi.getContext(), true).screen(0, null, 0, 0), "home_font130");
    }

    @Test public void largeFontPopup() {
        paparazzi.snapshot(new ShotHost(paparazzi.getContext(), false).screen(1, Controls.Surface.HEADSUP, 0, 0), "popup_font130");
    }
}

package io.github.igorcv88.oneuiliquidglass.shots;

import app.cash.paparazzi.Paparazzi;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import org.junit.Rule;
import org.junit.Test;

public class LandscapeShots {
    @Rule public Paparazzi paparazzi = new Paparazzi(app.cash.paparazzi.EnvironmentKt.detectEnvironment(), Devices.landscape());

    @Test public void landscapeSurfaces() {
        paparazzi.snapshot(new ShotHost(paparazzi.getContext(), true).screen(1, Controls.Surface.SHADE, 0, 0), "shade_landscape");
    }
}

package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;

/** Package access to the sheets for screenshots. */
public final class ShotsAccess {
    private ShotsAccess() {}
    public static Sheet control(Host h, String key, Controls.Surface s) { return new ControlSheet(h, key, s); }
    public static Sheet lens(Host h) { return new LensSheet(h); }
    public static Sheet tests(Host h) { return InfoSheets.testsSheet(h); }
}

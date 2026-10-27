package io.github.igorcv88.oneuiliquidglass.shots;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.ui.Host;
import io.github.igorcv88.oneuiliquidglass.ui.ShotsAccess;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;

final class Shots {
    static Sheet control(Host h, String key, Controls.Surface s) { return ShotsAccess.control(h, key, s); }
    static Sheet lens(Host h) { return ShotsAccess.lens(h); }
    static Sheet tests(Host h) { return ShotsAccess.tests(h); }
}

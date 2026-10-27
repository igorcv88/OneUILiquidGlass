package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.config.Controls;

/** Entry points to the sheets for the activity shell. */
public final class ControlSheets {
    private ControlSheets() {}

    public static void open(Host host, String key, Controls.Surface surface) {
        new ControlSheet(host, key, surface).show();
    }

    public static void tests(Host host) {
        InfoSheets.tests(host);
    }

    public static void lens(Host host) {
        new LensSheet(host).show();
    }

    public static void sync(Host host) {
        InfoSheets.sync(host);
    }

    public static void conflicts(Host host) {
        InfoSheets.conflicts(host);
    }

    public static void review(Host host, String json) {
        PresetsSheet.PresetReview.open(host, json);
    }
}

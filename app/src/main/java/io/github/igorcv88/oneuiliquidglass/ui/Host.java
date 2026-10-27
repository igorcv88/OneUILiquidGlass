package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;

/** What screens and sheets need from the activity shell. */
public interface Host {
    Activity activity();

    Theme theme();

    AppModel model();

    /** A value being dragged: drafts the model and refreshes the visible sample. */
    void preview(String key, String value, Controls.Channel channel);

    /** Drag ended (saved or not): hides the magnifier. */
    void endPreview();

    /** Short, discreet confirmation or problem shown above the navigation bar. */
    void feedback(String message);

    void openControl(String key, Controls.Surface surface);

    void showSurface(Controls.Surface surface);

    /** Publishes the main test notification (permission flow included). */
    void testNotification();

    void openTestOptions();

    void openLens();

    void openSync();

    void openConflicts();

    void requestTest(int count, int delayMs);

    void copy(String text);

    /** Rebuild views after a theme or motion preference change, keeping position. */
    void rebuild();

    void exportSettings();

    void importSettings();
}

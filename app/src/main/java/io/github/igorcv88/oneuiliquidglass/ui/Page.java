package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

/** A destination of the navigation bar. Built once; {@link #bind} updates it in place. */
public interface Page {
    View view();

    ScrollView scroller();

    /** Refresh texts, values and enabled states from the model. Cheap; never rebuilds. */
    void bind();

    /** Space reserved at the top (status bar) and bottom (navigation bar + gesture area). */
    void insets(int top, int bottom);

    default void save(Bundle out) {}

    default void restore(Bundle in) {}
}

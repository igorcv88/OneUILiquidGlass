package io.github.igorcv88.oneuiliquidglass.glass;

import android.view.View;

/**
 * A backdrop whose pixels the material shader samples (and refracts) itself, instead of a blur the
 * compositor draws under the view. {@link #frame()} is null until the first frame arrives.
 */
public interface SampledBackdrop extends Backdrop {
    CaptureHub.Frame frame();
    View view();
    /** Set when the source stopped being usable; the owner replaces this backdrop. */
    boolean unavailable();
}

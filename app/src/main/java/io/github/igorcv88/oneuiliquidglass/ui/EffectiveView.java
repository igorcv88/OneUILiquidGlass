package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;

/**
 * The effective state of many controls from one plan: the plan is a fixed-point computation, so a
 * screen builds this once per bind instead of once per row. A null plan (contradictory saved
 * values) leaves the rows with what the rules say.
 */
final class EffectiveView {
    private final ControlState state;
    private final PublicationPlan plan;
    private final boolean lensIntent;

    EffectiveView(ControlState state, PublicationPlan plan, boolean lensIntent) {
        this.state = state;
        this.plan = plan;
        this.lensIntent = lensIntent;
    }

    EffectiveState.Effective of(String key, Controls.Surface surface) {
        return EffectiveState.of(state, plan, key, surface, null, lensIntent);
    }
}

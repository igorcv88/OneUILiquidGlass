package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;

/**
 * The four independent hosts of the laudo (02/11/2026): L1 chip, L2 expanded card, L3 Now Bar,
 * T1 toast. Each has its own owner, writers, material and failure budget; a failure in one never
 * disables another. L1 and L2 share the "live update at the top" switch.
 */
public enum LiveSurface {
    // Region colour = native RGB at a fraction of its alpha; rim lit from the upper left.
    CHIP(ProductFlags.LIVE_ACTIVITY, new LiveGlassDrawable.Spec(0.52f, 60, 0x66ffffff, 0x14ffffff, 0.8f, 0x29ffffff)),
    CARD(ProductFlags.LIVE_ACTIVITY, new LiveGlassDrawable.Spec(0.42f, 80, 0x59ffffff, 0x12ffffff, 0.95f, 0x29ffffff)),
    NOWBAR(ProductFlags.NOW_BAR, new LiveGlassDrawable.Spec(0.38f, 80, 0x59ffffff, 0x12ffffff, 0.95f, 0x24ffffff)),
    TOAST(ProductFlags.TOAST, new LiveGlassDrawable.Spec(0.46f, 80, 0x59ffffff, 0x12ffffff, 0.95f, 0x29ffffff));

    public final String flag;
    public final LiveGlassDrawable.Spec spec;
    LiveSurface(String flag, LiveGlassDrawable.Spec spec) { this.flag = flag; this.spec = spec; }
}

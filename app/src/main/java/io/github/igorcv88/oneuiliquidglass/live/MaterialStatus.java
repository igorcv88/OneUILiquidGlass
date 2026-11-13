package io.github.igorcv88.oneuiliquidglass.live;

import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.Mode;

import java.util.Set;

/**
 * Where the chosen glass material stands, from the evidence the app already collects. A saved
 * choice, a written property and a compiled shader are different facts; only a verified restart
 * establishes the last one, so nothing here reports a material as applied from a setprop alone.
 */
public enum MaterialStatus {
    /** The selectors differ or are per-card: set outside the single choice. */
    MIXED,
    /** Clear is chosen but the compositor is not running the dynamic lens: saved, cannot apply. */
    UNAVAILABLE,
    /** Saved, and the running compositor was compiled with something else. */
    PENDING,
    /** The values match but no verified restart proves what the shaders were compiled with. */
    UNCONFIRMED,
    /** The last verified restart compiled this material. */
    APPLIED,
    /** Frosted without the compositor: the module's own glass, nothing to compile. */
    NOT_REQUIRED;

    /**
     * @param desired the material both selectors stand for, null when they do not agree
     * @param mode the rendering path (DYNAMIC_LENS only when the library is loaded)
     * @param pendingKeys compositor keys whose saved or device value differs from the compile
     * @param unverified {@code AppModel.compositorUnverified()}
     * @param lastApply outcome of the last restart requested from the app
     */
    public static MaterialStatus of(
            GlassMaterial desired,
            Mode mode,
            Set<String> pendingKeys,
            boolean unverified,
            CompositorValues.Outcome lastApply) {
        if (desired == null) return MIXED;
        boolean lens = mode == Mode.DYNAMIC_LENS;
        if (desired == GlassMaterial.CLEAR && !lens) return UNAVAILABLE;
        if (pendingKeys.contains(GlassMaterial.HU_SELECTOR) || pendingKeys.contains(GlassMaterial.KG_SELECTOR))
            return PENDING;
        if (!lens) return NOT_REQUIRED;
        if (unverified
                || lastApply == CompositorValues.Outcome.NOT_LOADED
                || lastApply == CompositorValues.Outcome.CHANGED)
            return UNCONFIRMED;
        return APPLIED;
    }
}

package io.github.igorcv88.oneuiliquidglass.config.product;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.Mode;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;

import java.util.function.Function;

/**
 * The layout route of a surface (docs/OULG_UI_PLAN/EFFECT_MAP.md section 1), from confirmed values
 * only. With the lens intent, pass {@link ConfirmedSnapshot#nextReader()} so the lens routes stay
 * visible and editable while the lens is not loaded.
 */
public final class Routes {
    private Routes() {}

    public static Route of(ConfirmedSnapshot snapshot, Controls.Surface surface) {
        return of(snapshot::value, surface);
    }

    /** {@code value} returns what the readers use for a registry key; never a draft. */
    public static Route of(Function<String, String> value, Controls.Surface surface) {
        switch (Mode.of(value)) {
            case CAPTURE:
                return Route.CAPTURE;
            case PLAIN_BLUR:
                return Route.PLAIN_BLUR;
            case DYNAMIC_LENS:
                return lens(value, surface);
            default:
                return Route.STANDARD;
        }
    }

    private static Route lens(Function<String, String> value, Controls.Surface surface) {
        if (surface == Controls.Surface.SHADE) return Route.LENS_FROSTED;
        GlassMaterial material = GlassMaterial.forSurface(value, surface);
        if (material == null) return Route.LENS_MIXED;
        return material == GlassMaterial.CLEAR ? Route.LENS_CLEAR : Route.LENS_FROSTED;
    }
}

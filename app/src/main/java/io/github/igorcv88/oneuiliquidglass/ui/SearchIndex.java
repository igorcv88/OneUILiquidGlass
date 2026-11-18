package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;

import java.text.Normalizer;
import java.util.Locale;

/**
 * What the search lists and how it names where a control lives (docs/OULG_UI_PLAN/SCREEN_SPECS.md
 * section 3): product titles are indexed with the existing text, legacy controls never appear, and
 * technical or diagnostic ones only when technical controls are asked for.
 */
final class SearchIndex {
    private SearchIndex() {}

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Whether a control may be listed at all. */
    static boolean listed(Controls.Control c, boolean showTechnical) {
        ProductLayout.Destination d = ProductLayout.destination(c.key);
        // Energy lives only in Settings > Energia; never in Search, not even in the technical list.
        if (d == ProductLayout.Destination.LEGACY || d == ProductLayout.Destination.ENERGY) return false;
        if (c.tier == Controls.Tier.INTERNAL) return showTechnical;
        if (d == ProductLayout.Destination.TECHNICAL || d == ProductLayout.Destination.DIAGNOSTIC
                || d == ProductLayout.Destination.MATERIAL) return showTechnical;
        return true;
    }

    /** The product title, the current title, hints, help and the technical name, folded for matching. */
    static boolean matches(Context context, Controls.Control c, String query) {
        String hay = ProductTitles.title(context, c) + " " + ControlTitles.title(context, c) + " " + c.title + " "
                + ControlDescriptions.hint(context, c) + " " + ControlDescriptions.help(context, c) + " " + c.hint + " "
                + c.help + " " + c.spec().fullKey() + " " + c.section.label + " " + UiLabels.section(context, c.section);
        return fold(hay).contains(fold(query == null ? "" : query.trim()));
    }

    /**
     * "Pop-up › Details › Blur": where the control lives on a surface and route. A key that the
     * current route does not show is placed in the group it has on other routes.
     */
    static String path(Context context, Controls.Control c, Controls.Surface surface, Route route, String surfaceName) {
        String sep = " › ";
        ProductLayout.Destination d = ProductLayout.destination(c.key);
        if (d == ProductLayout.Destination.ICONS) return context.getString(R.string.nav_icons);
        if (d == ProductLayout.Destination.TECHNICAL || d == ProductLayout.Destination.DIAGNOSTIC
                || d == ProductLayout.Destination.MATERIAL)
            return context.getString(R.string.settings) + sep + context.getString(R.string.settings_technical);
        if (d == ProductLayout.Destination.QS_MAIN)
            return surfaceName + sep + context.getString(R.string.section_qs);
        if (d == ProductLayout.Destination.QS_DETAIL)
            return surfaceName + sep + context.getString(R.string.section_qs) + sep + context.getString(R.string.details_title);
        if (ProductLayout.main(surface, route).contains(c.key))
            return surfaceName + sep + context.getString(R.string.search_path_main);
        if (ProductLayout.behavior(surface).contains(c.key))
            return surfaceName + sep + context.getString(R.string.section_behavior);
        ProductLayout.Group group = group(surface, route, c.key);
        for (Route r : Route.values()) if (group == null) group = group(surface, r, c.key);
        if (group == null) return surfaceName;
        return surfaceName + sep + context.getString(R.string.details_title) + sep + context.getString(group.titleRes);
    }

    private static ProductLayout.Group group(Controls.Surface surface, Route route, String key) {
        for (ProductLayout.Group g : ProductLayout.details(surface, route)) if (g.keys.contains(key)) return g;
        return null;
    }
}

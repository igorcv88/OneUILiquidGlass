package io.github.igorcv88.oneuiliquidglass.ui.surfaces;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ordered sections of the surfaces page for a surface and a route, with no View: favorites,
 * main controls, appearance detail groups, the quick panel (shade) and behavior
 * (docs/OULG_UI_PLAN/SCREEN_SPECS.md section 2). A key appears once; empty sections are left out.
 */
public final class SurfacesPlan {
    public enum Kind { FAVORITES, MAIN, DETAIL_GROUP, QS_MAIN, QS_DETAILS, BEHAVIOR }

    public static final class Section {
        public final Kind kind;
        /** The detail group id, else the kind name. */
        public final String id;
        public final int titleRes;
        public final List<String> keys;

        Section(Kind kind, String id, int titleRes, List<String> keys) {
            this.kind = kind;
            this.id = id;
            this.titleRes = titleRes;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }
    }

    private static final Set<ProductLayout.Destination> FAVORITE_DESTINATIONS = EnumSet.of(
            ProductLayout.Destination.MAIN, ProductLayout.Destination.DETAIL, ProductLayout.Destination.BEHAVIOR,
            ProductLayout.Destination.QS_MAIN, ProductLayout.Destination.QS_DETAIL);

    public final List<Section> sections;

    private SurfacesPlan(List<Section> sections) {
        this.sections = Collections.unmodifiableList(sections);
    }

    public static SurfacesPlan of(Controls.Surface surface, Route route, Set<String> favorites) {
        Set<String> placed = new HashSet<>();
        List<Section> out = new ArrayList<>();
        add(out, placed, Kind.FAVORITES, Kind.FAVORITES.name(), R.string.surface_favorites, new ArrayList<>(favorites));
        add(out, placed, Kind.MAIN, Kind.MAIN.name(), R.string.surface_main, ProductLayout.main(surface, route));
        for (ProductLayout.Group g : ProductLayout.details(surface, route))
            add(out, placed, Kind.DETAIL_GROUP, g.id, g.titleRes, g.keys);
        if (surface == Controls.Surface.SHADE) {
            add(out, placed, Kind.QS_MAIN, Kind.QS_MAIN.name(), R.string.section_qs, ProductLayout.quickMain());
            add(out, placed, Kind.QS_DETAILS, Kind.QS_DETAILS.name(), R.string.details_title, ProductLayout.quickDetails());
        }
        add(out, placed, Kind.BEHAVIOR, Kind.BEHAVIOR.name(), R.string.section_behavior, ProductLayout.behavior(surface));
        return new SurfacesPlan(out);
    }

    /** Keys the page can show: editable and not already placed above. */
    private static void add(List<Section> out, Set<String> placed, Kind kind, String id, int title, Collection<String> keys) {
        List<String> shown = new ArrayList<>();
        for (String k : keys) {
            Controls.Control c = Controls.get(k);
            if (c == null || !Controls.editable(k) || placed.contains(k)) continue;
            shown.add(k);
        }
        if (shown.isEmpty()) return;
        placed.addAll(shown);
        out.add(new Section(kind, id, title, shown));
    }

    /** Saved favorites the lists do not show: registry keys whose destination is not an everyday one. */
    public static List<String> hiddenFavorites(Collection<String> favored) {
        List<String> out = new ArrayList<>();
        for (String key : favored) {
            Controls.Control c = Controls.get(key);
            if (c != null && !favoriteAllowed(key)) out.add(key);
        }
        return out;
    }

    /** Whether a key may be a favorite: main, detail, behavior or quick-panel controls only. */
    public static boolean favoriteAllowed(String key) {
        return FAVORITE_DESTINATIONS.contains(ProductLayout.destination(key));
    }

    /**
     * The favorites a surface list may show: only controls whose destination is an everyday one.
     * The saved flags of technical or legacy keys are neither deleted nor listed here.
     */
    public static Set<String> favorites(Controls.Surface surface, Collection<String> favored) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : favored) {
            Controls.Control c = Controls.get(key);
            if (c != null && c.on(surface) && Controls.editable(key)
                    && favoriteAllowed(key)) out.add(key);
        }
        return out;
    }
}

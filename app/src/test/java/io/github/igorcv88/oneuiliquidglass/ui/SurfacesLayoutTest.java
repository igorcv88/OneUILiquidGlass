package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan.Kind;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan.Section;
import java.util.*;
import org.junit.Test;

/** The ordered sections and keys of the surfaces page, without a View (docs SCREEN_SPECS section 2). */
public class SurfacesLayoutTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP, KG = Controls.Surface.KEYGUARD,
            SHADE = Controls.Surface.SHADE;
    private static final Set<String> NONE = Collections.emptySet();

    private static List<Kind> kinds(SurfacesPlan p) {
        List<Kind> out = new ArrayList<>();
        for (Section s : p.sections) out.add(s.kind);
        return out;
    }

    private static Section first(SurfacesPlan p, Kind kind) {
        for (Section s : p.sections) if (s.kind == kind) return s;
        return null;
    }

    @Test
    public void clearHeadsUpShowsTheFiveMainControlsThenDetailGroups() {
        SurfacesPlan p = SurfacesPage.plan(HU, Route.LENS_CLEAR, NONE);
        assertEquals(Arrays.asList("sf.hublur", "sf.hurefract", "sf.hclift", "sf.hcsat", "sf.hcfold"),
                first(p, Kind.MAIN).keys);
        assertEquals(Kind.MAIN, p.sections.get(0).kind);
        assertTrue(kinds(p).contains(Kind.DETAIL_GROUP));
        assertFalse(kinds(p).contains(Kind.FAVORITES));
        assertFalse(kinds(p).contains(Kind.QS_MAIN));
    }

    @Test
    public void favoritesComeFirstAndAreNotRepeatedBelow() {
        Set<String> fav = new LinkedHashSet<>(Arrays.asList("sf.hclift", "sf.hcedge"));
        SurfacesPlan p = SurfacesPage.plan(HU, Route.LENS_CLEAR, fav);
        assertEquals(Kind.FAVORITES, p.sections.get(0).kind);
        assertEquals(new ArrayList<>(fav), p.sections.get(0).keys);
        Set<String> seen = new HashSet<>();
        for (Section s : p.sections)
            for (String k : s.keys) assertTrue(k + " repeated", seen.add(k));
    }

    @Test
    public void everyKeyIsOnItsSurfaceAndEditableOnEveryRoute() {
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values())
                for (Section sec : SurfacesPage.plan(s, r, NONE).sections)
                    for (String k : sec.keys) {
                        Controls.Control c = Controls.get(k);
                        assertNotNull(k, c);
                        assertTrue(k + " on " + s, c.on(s));
                        assertTrue(k + " editable", Controls.editable(k));
                    }
    }

    @Test
    public void detailGroupsFollowTheMappingOrderAndNoSectionIsEmpty() {
        SurfacesPlan frosted = SurfacesPage.plan(HU, Route.LENS_FROSTED, NONE);
        List<String> ids = new ArrayList<>();
        for (Section s : frosted.sections) if (s.kind == Kind.DETAIL_GROUP) ids.add(s.id);
        assertEquals(Arrays.asList("blur", "refraction", "light", "color", "tone"), ids);
        for (Section s : frosted.sections) assertFalse(s.keys.isEmpty());
    }

    @Test
    public void theShadeAddsTheQuickPanelAfterTheDetailsAndBehaviorLast() {
        SurfacesPlan p = SurfacesPage.plan(SHADE, Route.STANDARD, NONE);
        List<Kind> k = kinds(p);
        assertTrue(k.indexOf(Kind.QS_MAIN) > k.lastIndexOf(Kind.DETAIL_GROUP));
        assertTrue(k.indexOf(Kind.QS_DETAILS) > k.indexOf(Kind.QS_MAIN));
        assertEquals(Kind.BEHAVIOR, k.get(k.size() - 1));
        assertEquals(ProductLayout.quickMain().size(), first(p, Kind.QS_MAIN).keys.size());
    }

    @Test
    public void quickPanelAndBehaviorOnlyAppearWhereTheyBelong() {
        assertFalse(kinds(SurfacesPage.plan(HU, Route.STANDARD, NONE)).contains(Kind.QS_MAIN));
        assertNotNull(first(SurfacesPage.plan(KG, Route.STANDARD, NONE), Kind.BEHAVIOR));
    }

    @Test
    public void technicalKeysNeverReachTheSurfacePage() {
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values())
                for (Section sec : SurfacesPage.plan(s, r, NONE).sections)
                    for (String k : sec.keys) {
                        ProductLayout.Destination d = ProductLayout.destination(k);
                        assertTrue(k, d != ProductLayout.Destination.TECHNICAL && d != ProductLayout.Destination.DIAGNOSTIC
                                && d != ProductLayout.Destination.LEGACY && d != ProductLayout.Destination.MATERIAL);
                    }
    }

    @Test
    public void mixedRouteShowsFrostedMainControlsThenTheClearOnes() {
        List<String> main = first(SurfacesPage.plan(HU, Route.LENS_MIXED, NONE), Kind.MAIN).keys;
        assertTrue(main.indexOf("sf.hulight") < main.indexOf("sf.hclift"));
        assertEquals(main.size(), new HashSet<>(main).size());
    }

    @Test
    public void favoritesKeepOnlyEverydayDestinationsOnThisSurface() {
        List<String> favored = Arrays.asList("sf.hclift", "backdrop", "qs.idle", "sf.huclear", "liveradius", "kgblurradius");
        Set<String> shown = SurfacesPlan.favorites(HU, favored);
        assertEquals(new LinkedHashSet<>(Arrays.asList("sf.hclift", "liveradius")), shown);
    }
}

package io.github.igorcv88.oneuiliquidglass.config.product;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Destination;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Group;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import java.util.*;
import org.junit.Test;

/** The generated layout covers every key once and keeps technical keys out of the everyday lists. */
public class ProductLayoutTest {
    private static final Set<Destination> EVERYDAY = EnumSet.of(Destination.MAIN, Destination.DETAIL,
            Destination.BEHAVIOR, Destination.QS_MAIN, Destination.QS_DETAIL);

    @Test
    public void everyRegistryKeyHasOneDestination() {
        int registry = SettingsRegistry.all().size();
        for (SettingSpec s : SettingsRegistry.all())
            assertNotNull(s.key + " has no destination", ProductLayout.destination(s.key));
        assertEquals("the energy keys are registry keys since phase D", registry, ProductLayout.keys().size());
        assertEquals(6, ProductLayout.energy().size());
        for (String key : ProductLayout.energy()) assertEquals(key, Destination.ENERGY, ProductLayout.destination(key));
    }

    @Test
    public void noTechnicalDiagnosticOrLegacyKeyIsMainOrDetail() {
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values()) {
                List<String> keys = new ArrayList<>(ProductLayout.main(s, r));
                for (Group g : ProductLayout.details(s, r)) keys.addAll(g.keys);
                keys.addAll(ProductLayout.behavior(s));
                for (String k : keys)
                    assertTrue(k + " on " + s + "/" + r, EVERYDAY.contains(ProductLayout.destination(k)));
            }
    }

    @Test
    public void clearHeadsUpMainControlsAreTheLensFive() {
        assertEquals(Arrays.asList("sf.hublur", "sf.hurefract", "sf.hclift", "sf.hcsat", "sf.hcfold"),
                ProductLayout.main(Controls.Surface.HEADSUP, Route.LENS_CLEAR));
    }

    @Test
    public void brighteningIsMainSoItIsAboveEveryHighlightControl() {
        List<String> main = ProductLayout.main(Controls.Surface.HEADSUP, Route.LENS_CLEAR);
        assertTrue(main.contains("sf.hclift"));
        for (Group g : ProductLayout.details(Controls.Surface.HEADSUP, Route.LENS_CLEAR))
            assertFalse("main control repeated in details", g.keys.contains("sf.hclift"));
    }

    @Test
    public void classicWhiteNeutralityFollowsHighlightNeutralityInColor() {
        for (Route r : new Route[] {Route.LENS_CLEAR, Route.LENS_MIXED}) {
            Group color = group(Controls.Surface.HEADSUP, r, "color");
            assertTrue(color.keys.indexOf("sf.hcneutral") > color.keys.indexOf("sf.hchlneutral"));
        }
    }

    @Test
    public void mixedRouteHasNoDuplicatesAndKeepsFrostedBeforeClearOnly() {
        for (Controls.Surface s : Controls.Surface.values()) {
            List<String> all = new ArrayList<>(ProductLayout.main(s, Route.LENS_MIXED));
            Set<String> seen = new HashSet<>(all);
            assertEquals("main " + s, all.size(), seen.size());
            for (Group g : ProductLayout.details(s, Route.LENS_MIXED))
                for (String k : g.keys) assertTrue("duplicate " + k + " on " + s, seen.add(k));
        }
        List<String> mixed = ProductLayout.main(Controls.Surface.HEADSUP, Route.LENS_MIXED);
        assertTrue(mixed.indexOf("sf.hulight") < mixed.indexOf("sf.hclift"));
    }

    @Test
    public void shadeUnderTheLensUsesTheFrostedList() {
        assertEquals(ProductLayout.main(Controls.Surface.SHADE, Route.LENS_FROSTED),
                ProductLayout.main(Controls.Surface.SHADE, Route.LENS_CLEAR));
        assertEquals(ProductLayout.main(Controls.Surface.SHADE, Route.LENS_FROSTED),
                ProductLayout.main(Controls.Surface.SHADE, Route.LENS_MIXED));
    }

    @Test
    public void detailGroupsAreInProductOrderAndNeverEmpty() {
        List<String> order = Arrays.asList("blur", "refraction", "light", "color", "tone");
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values()) {
                int last = -1;
                for (Group g : ProductLayout.details(s, r)) {
                    assertFalse(g.keys.isEmpty());
                    int at = order.indexOf(g.id);
                    assertTrue(at > last);
                    last = at;
                }
            }
    }

    @Test
    public void routeSpecificDetailsAreHiddenOutsideTheirRoutes() {
        assertNull(find(ProductLayout.details(Controls.Surface.HEADSUP, Route.STANDARD), "sf.hcedge"));
        assertNull(find(ProductLayout.details(Controls.Surface.HEADSUP, Route.LENS_FROSTED), "sf.hcedge"));
        assertNotNull(find(ProductLayout.details(Controls.Surface.HEADSUP, Route.LENS_CLEAR), "sf.hcedge"));
        assertNotNull(find(ProductLayout.details(Controls.Surface.HEADSUP, Route.LENS_FROSTED), "sf.edgeblur"));
    }

    @Test
    public void technicalGroupsKeepEverySelectorAndReadOnlyKeyOutOfTheSurfaces() {
        assertTrue(ProductLayout.materialSelectors().contains("sf.huclear"));
        assertEquals(Destination.MATERIAL, ProductLayout.destination("sf.kgclear"));
        assertEquals(Destination.DIAGNOSTIC, ProductLayout.destination("sfrefract"));
        assertEquals(Destination.LEGACY, ProductLayout.destination("qs.idle"));
        assertEquals(Destination.TECHNICAL, ProductLayout.destination("backdrop"));
        assertEquals(8, ProductLayout.technical().size());
    }

    @Test
    public void multiplierParentsDisplayAsPercentAndEverythingElseAsTheyAre() {
        assertEquals(ProductLayout.Display.MULTIPLIER_PERCENT, ProductLayout.display("sf.hublur"));
        assertEquals(ProductLayout.Display.MULTIPLIER_PERCENT, ProductLayout.display("sf.kgrefract"));
        assertEquals(ProductLayout.Display.DEFAULT, ProductLayout.display("sf.hclift"));
    }

    @Test
    public void productTitlesExistOnlyWhereTheMappingNamesThem() {
        assertNotEquals(0, ProductLayout.productTitle("sf.hublur"));
        assertEquals(ProductLayout.productTitle("sf.hublur"), ProductLayout.productTitle("sf.kgblur"));
        assertEquals(0, ProductLayout.productTitle("sf.hcband"));
    }

    private static Group group(Controls.Surface s, Route r, String id) {
        for (Group g : ProductLayout.details(s, r)) if (g.id.equals(id)) return g;
        fail("no group " + id);
        return null;
    }

    private static Group find(List<Group> groups, String key) {
        for (Group g : groups) if (g.keys.contains(key)) return g;
        return null;
    }
}

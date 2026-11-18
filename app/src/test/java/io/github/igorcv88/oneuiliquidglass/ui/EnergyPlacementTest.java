package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The energy keys appear only in Settings > Energia: not on Surfaces, not in Search, not in Technical. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class EnergyPlacementTest {
    @Test
    public void sixKeysAllInTheEnergySection() {
        assertEquals(6, ProductLayout.energy().size());
        for (String key : ProductLayout.energy()) {
            assertEquals(key, Controls.Section.ENERGY, Controls.get(key).section);
            assertEquals(key, ProductLayout.Destination.ENERGY, ProductLayout.destination(key));
        }
    }

    @Test
    public void neverOnASurfacePageOrAsFavorite() {
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values())
                for (SurfacesPlan.Section section : SurfacesPlan.of(s, r, SurfacesPlan.favorites(s, ProductLayout.energy())).sections)
                    for (String key : section.keys)
                        assertFalse(key + " on " + s + "/" + r, ProductLayout.energy().contains(key));
        for (String key : ProductLayout.energy()) assertFalse(key, SurfacesPlan.favoriteAllowed(key));
        for (Controls.Surface s : Controls.Surface.values())
            assertTrue(SurfacesPlan.favorites(s, ProductLayout.energy()).isEmpty());
    }

    @Test
    public void neverInSearchNorInTechnical() {
        for (String key : ProductLayout.energy()) {
            assertFalse(key, SearchIndex.listed(Controls.get(key), false));
            assertFalse(key + " (technical list)", SearchIndex.listed(Controls.get(key), true));
        }
        Set<String> technical = new HashSet<>();
        for (ProductLayout.Group g : ProductLayout.technical()) technical.addAll(g.keys);
        technical.addAll(ProductLayout.readOnly());
        technical.addAll(ProductLayout.legacy());
        for (String key : ProductLayout.energy()) assertFalse(key, technical.contains(key));
    }
}

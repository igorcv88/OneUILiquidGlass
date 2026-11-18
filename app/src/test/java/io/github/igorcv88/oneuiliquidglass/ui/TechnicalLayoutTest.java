package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/** Technical, read-only and legacy controls live in Settings only; "per card" is a technical save. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class TechnicalLayoutTest {
    private Context context;
    private ActivityController<MainActivity> controller;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        Config.open(context).edit().clear().putBoolean(Config.KEY_ENABLED, true).commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void cleanUp() {
        if (controller != null) controller.pause().stop().destroy();
        Config.open(context).edit().clear().commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void technicalGroupsAreNeverOnASurfacePage() {
        Set<String> technical = new HashSet<>();
        for (ProductLayout.Group g : ProductLayout.technical()) technical.addAll(g.keys);
        technical.addAll(ProductLayout.readOnly());
        technical.addAll(ProductLayout.legacy());
        assertFalse(technical.isEmpty());
        for (Controls.Surface s : Controls.Surface.values())
            for (Route r : Route.values())
                for (SurfacesPlan.Section section : SurfacesPlan.of(s, r, Collections.emptySet()).sections)
                    for (String key : section.keys)
                        assertFalse(key + " on " + s + "/" + r, technical.contains(key));
    }

    @Test
    public void everyTechnicalKeyIsInTheRegistryAndHasAGroupTitle() {
        for (ProductLayout.Group g : ProductLayout.technical()) {
            assertTrue(g.id, g.titleRes != 0);
            for (String key : g.keys) assertNotNull(key, Controls.get(key));
        }
        for (String key : ProductLayout.legacy()) assertNotNull(key, Controls.get(key));
    }

    @Test
    public void perCardSavesBothSelectorsAsTwoInOneEditAndIsReportedAsSelected() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        AppModel m = controller.get().model();
        assertFalse(m.perCardSelected());
        AppModel.Result r = m.selectPerCard();
        assertTrue(r.message, r.ok);
        assertEquals("2", m.state().saved.get(GlassMaterial.HU_SELECTOR));
        assertEquals("2", m.state().saved.get(GlassMaterial.KG_SELECTOR));
        assertTrue(m.perCardSelected());
    }

    @Test
    public void choosingAMaterialAfterPerCardLeavesPerCardBehind() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        AppModel m = controller.get().model();
        m.selectPerCard();
        assertTrue(m.selectMaterial(GlassMaterial.FROSTED).ok);
        assertFalse(m.perCardSelected());
    }
}

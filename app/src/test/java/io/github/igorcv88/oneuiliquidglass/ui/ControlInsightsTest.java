package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import java.util.Arrays;
import java.util.Locale;
import org.junit.Test;

/** The "Affects" and "Effective nominal" facts of the multiplier editors. */
public class ControlInsightsTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP, KG = Controls.Surface.KEYGUARD;

    @Test
    public void blurParentAffectsTheEdgeAndCenterBlurOfTheRoute() {
        assertEquals(Arrays.asList("sf.hcedge", "sf.hccore"), ControlInsights.affected(HU, Route.LENS_CLEAR, "sf.hublur"));
        assertEquals(Arrays.asList("sf.edgeblur", "sf.core"), ControlInsights.affected(HU, Route.LENS_FROSTED, "sf.hublur"));
        assertEquals(Arrays.asList("sf.kcedge", "sf.kccore"), ControlInsights.affected(KG, Route.LENS_CLEAR, "sf.kgblur"));
    }

    @Test
    public void refractionParentAffectsTheShapeControlsOfTheRoute() {
        java.util.List<String> affected = ControlInsights.affected(HU, Route.LENS_CLEAR, "sf.hurefract");
        assertTrue(affected.contains("sf.hcfold"));
        assertTrue(affected.contains("sf.hcband"));
        assertFalse(affected.contains("sf.hurefract"));
    }

    @Test
    public void nothingIsAffectedOutsideTheLensRoutes() {
        assertTrue(ControlInsights.affected(HU, Route.STANDARD, "sf.hublur").isEmpty());
        assertTrue(ControlInsights.affected(HU, Route.LENS_CLEAR, "sf.hclift").isEmpty());
    }

    @Test
    public void childrenKnowTheirParentAndTheRouteMustHaveIt() {
        assertEquals("sf.hublur", ControlInsights.parentOf("sf.hcedge"));
        assertEquals("sf.kgblur", ControlInsights.parentOf("sf.kgcore"));
        assertNull(ControlInsights.parentOf("sf.hclift"));
        assertTrue(ControlInsights.routeHasParent(HU, Route.LENS_CLEAR, "sf.hublur"));
        assertFalse(ControlInsights.routeHasParent(HU, Route.STANDARD, "sf.hublur"));
    }

    @Test
    public void nominalPixelsMultiplyTheChildByTheParentWithOneDecimal() {
        assertEquals("27,6 px", ControlInsights.nominalPx(Locale.forLanguageTag("pt-BR"), 24, 1.15));
        assertEquals("34.5 px", ControlInsights.nominalPx(Locale.US, 30, 1.15));
        assertEquals("24 px", ControlInsights.nominalPx(Locale.US, 24, 1));
    }
}

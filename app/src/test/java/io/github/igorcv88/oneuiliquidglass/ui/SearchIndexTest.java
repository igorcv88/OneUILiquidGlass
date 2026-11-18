package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The search lists everyday controls, hides legacy ones and gates technical ones. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class SearchIndexTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private static Controls.Control control(String key) {
        Controls.Control c = Controls.get(key);
        assertNotNull(key, c);
        return c;
    }

    @Test
    public void everydayControlsAreAlwaysListed() {
        assertTrue(SearchIndex.listed(control("sf.hclift"), false));
        assertTrue(SearchIndex.listed(control("sf.hcedge"), false));
        assertTrue(SearchIndex.listed(control("popuplock"), false));
        assertTrue(SearchIndex.listed(control("iconmode"), false));
    }

    @Test
    public void technicalAndDiagnosticControlsNeedTheTechnicalToggle() {
        assertFalse(SearchIndex.listed(control("backdrop"), false));
        assertTrue(SearchIndex.listed(control("backdrop"), true));
        assertFalse(SearchIndex.listed(control("perf"), false));
        assertTrue(SearchIndex.listed(control("perf"), true));
        assertFalse("the material selectors are technical", SearchIndex.listed(control("sf.huclear"), false));
    }

    @Test
    public void legacyControlsNeverAppear() {
        assertFalse(SearchIndex.listed(control("qs.idle"), true));
        assertFalse(SearchIndex.listed(control("qs.idle"), false));
    }

    @Test
    public void theProductTitleIsIndexed() {
        assertTrue(SearchIndex.matches(context, control("sf.hclift"), "clareamento")
                || SearchIndex.matches(context, control("sf.hclift"), "brightening"));
        assertTrue("both blur parents share the product title Blur",
                SearchIndex.matches(context, control("sf.hublur"), "blur")
                        && SearchIndex.matches(context, control("sf.kgblur"), "blur"));
        assertTrue(SearchIndex.matches(context, control("sf.hclift"), "BRIGHTENING".toLowerCase()));
    }

    @Test
    public void accentsAndCaseDoNotMatter() {
        assertEquals("refracao", SearchIndex.fold("Refração"));
        assertTrue(SearchIndex.matches(context, control("sf.hurefract"), "Refraction"));
    }

    @Test
    public void thePathNamesTheSurfaceTheGroupAndTheSection() {
        String main = SearchIndex.path(context, control("sf.hclift"), Controls.Surface.HEADSUP, Route.LENS_CLEAR, "Pop-up");
        assertEquals("Pop-up › Main", main);
        String detail = SearchIndex.path(context, control("sf.hcedge"), Controls.Surface.HEADSUP, Route.LENS_CLEAR, "Pop-up");
        assertTrue(detail, detail.startsWith("Pop-up › Details › "));
        String hiddenOnThisRoute = SearchIndex.path(context, control("sf.hcedge"), Controls.Surface.HEADSUP, Route.STANDARD, "Pop-up");
        assertEquals("a key the route does not show keeps its group", detail, hiddenOnThisRoute);
        assertTrue(SearchIndex.path(context, control("backdrop"), Controls.Surface.HEADSUP, Route.STANDARD, "Pop-up")
                .contains(context.getString(io.github.igorcv88.oneuiliquidglass.R.string.settings_technical)));
    }
}

package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
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

/** Favorites are filtered to everyday controls; the saved flags are kept and announced once. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class FavoritesFilterTest {
    private Context context;
    private SharedPreferences ui;
    private ActivityController<MainActivity> controller;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        Config.open(context).edit().clear().commit();
        ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        ui.edit().clear().commit();
    }

    @After
    public void cleanUp() {
        if (controller != null) controller.pause().stop().destroy();
        ui.edit().clear().commit();
    }

    private AppModel model() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get().model();
    }

    @Test
    public void onlyEverydayDestinationsOnThisSurfaceAreListed() {
        List<String> favored = Arrays.asList("sf.hclift", "backdrop", "qs.idle", "sf.huclear", "liveradius", "kgblurradius");
        Set<String> shown = SurfacesPlan.favorites(Controls.Surface.HEADSUP, favored);
        assertEquals(new LinkedHashSet<>(Arrays.asList("sf.hclift", "liveradius")), shown);
    }

    @Test
    public void technicalControlsCannotBeFavorited() {
        assertTrue(SurfacesPlan.favoriteAllowed("sf.hclift"));
        assertTrue(SurfacesPlan.favoriteAllowed("popuplock"));
        assertFalse(SurfacesPlan.favoriteAllowed("backdrop"));
        assertFalse(SurfacesPlan.favoriteAllowed("sfrefract"));
        assertFalse(SurfacesPlan.favoriteAllowed("qs.idle"));
    }

    @Test
    public void theMigrationNoticeCountsHiddenFavoritesOnceAndKeepsTheFlags() {
        ui.edit().putBoolean("favorite.backdrop", true).putBoolean("favorite.perf", true)
                .putBoolean("favorite.sf.hclift", true).putBoolean("favorite.liveradius", false).commit();
        AppModel m = model();
        // the pages bind at startup and may already have announced it: ask again from a clean flag
        ui.edit().remove("favorites.migrated.r6").commit();
        assertEquals(2, m.takeFavoritesMigrationNotice());
        assertEquals("shown once", 0, m.takeFavoritesMigrationNotice());
        assertTrue("flags are not deleted", ui.getBoolean("favorite.backdrop", false));
        assertTrue(ui.getBoolean("favorite.perf", false));
        assertTrue(ui.getBoolean("favorites.migrated.r6", false));
    }

    @Test
    public void withoutHiddenFavoritesTheNoticeIsZeroButTheFlagIsStillSet() {
        ui.edit().putBoolean("favorite.sf.hclift", true).commit();
        AppModel m = model();
        ui.edit().remove("favorites.migrated.r6").commit();
        assertEquals(0, m.takeFavoritesMigrationNotice());
        assertTrue(ui.getBoolean("favorites.migrated.r6", false));
    }
}

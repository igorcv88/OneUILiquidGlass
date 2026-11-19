package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.graphics.drawable.Drawable;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * debug.oulg.hublurpath (docs/clear-glass/HU_RESIDUAL.md): an opt-in comparison route. Off by
 * default; on it, the heads-up's drawable region takes the Samsung path's lens radius, not the
 * lockscreen's, and its alpha at rest is the module's 255.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class HeadsUpBlurPathTest {
    private static BackgroundBlurBridge lensBridge(Drawable d) throws Exception {
        Constructor<BackgroundBlurBridge> c = BackgroundBlurBridge.class.getDeclaredConstructor(Drawable.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(d, true);
    }

    @Test public void offByDefaultAndRegistered() {
        assertEquals(0, Tuning.get().huBlurPath);
        assertNotNull(SettingsRegistry.get("hublurpath"));
        assertEquals("0", SettingsRegistry.get("hublurpath").defaultValue);
    }

    @Test public void lensRadiusSelection() {
        // Existing lens drawables (lockscreen, heads-up over it): kgblurradius when set.
        assertEquals(12, BackgroundBlurBridge.lensPx(12, 14, false));
        assertEquals(14, BackgroundBlurBridge.lensPx(-1, 14, false));
        // The heads-up route keeps the Samsung path's semradiuslens, so a comparison isolates the route.
        assertEquals(14, BackgroundBlurBridge.lensPx(12, 14, true));
        assertEquals(20, BackgroundBlurBridge.lensPx(-1, 20, true));
    }

    @Test public void onlyLiveDrawsTheMeasuredPopupVeil() throws Exception {
        Field current = ConfigClient.class.getDeclaredField("current");
        current.setAccessible(true);
        Method refresh = Tuning.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        Object original = current.get(null);
        try {
            checkPopupVeil(current, refresh, true);
            checkPopupVeil(current, refresh, false);
        } finally {
            current.set(null, original);
            refresh.invoke(null);
        }
    }

    private static void checkPopupVeil(Field current, Method refresh, boolean live) throws Exception {
        current.set(null, Map.of("backdrop", "auto", "sfrefract", live ? "0" : "1", "liveblur", "1",
                "popuptone", "1", "semalpha", "32"));
        refresh.invoke(null);
        assertEquals(live, Tuning.get().live);
        FakeBlurDrawable d = new FakeBlurDrawable();
        BackgroundBlurBridge b = lensBridge(d);
        if (!live) b.useLensRadius();
        b.setOptics(false, true);
        int nativeTint = 0x20102030;
        int measuredVeil = 0x40b8c2ce;
        b.update(40, nativeTint, new float[8]);
        assertEquals(nativeTint, d.color);
        b.setPopupVeil(measuredVeil);
        assertEquals(live ? measuredVeil : nativeTint, d.color);
        b.setPopupVeil(0);
        assertEquals(nativeTint, d.color);
    }

    @Test public void headsUpRouteRegionIsWholeAtRest() throws Exception {
        FakeBlurDrawable d = new FakeBlurDrawable();
        BackgroundBlurBridge b = lensBridge(d);
        b.setOptics(false, true);
        b.update(40, 0, new float[8]);
        assertFalse(b.isHeadsUpLensRoute());
        b.useLensRadius();
        assertTrue(b.isHeadsUpLensRoute());
        // Samsung's 204 translucency never reaches the region: no share of the sharp backdrop.
        b.setAlpha(204);
        assertEquals(255, d.alpha);
        assertTrue(b.setRegionFade(0.5f));
        assertEquals(128, d.alpha);
        assertTrue(b.setRegionFade(1f));
        assertEquals(255, d.alpha);
    }
}

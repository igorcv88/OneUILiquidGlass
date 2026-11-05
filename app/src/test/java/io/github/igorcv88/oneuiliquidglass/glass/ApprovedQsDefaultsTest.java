package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.config.Rgba;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import org.junit.Test;

public class ApprovedQsDefaultsTest {
    @Test public void runtimeDefaultsMatchCatalogIncludingRgbaOrder() {
        QsStyle s = new QsStyle(k -> null);
        assertEquals(0f, s.lightFill, 0f);
        assertEquals(.36f, s.lightOptics, 0f);
        assertEquals(.28f, s.darkRim, 0f);
        assertEquals(6, s.edgeBlur);
        assertTrue(s.tileDark);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.sliderlightcolor").defaultValue), s.sliderLightColor);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.sliderdarkcolor").defaultValue), s.sliderDarkColor);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tilelighton").defaultValue), s.tileLightOn);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tilelightoff").defaultValue), s.tileLightOff);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tilelightonink").defaultValue), s.tileLightOnInk);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tilelightoffink").defaultValue), s.tileLightOffInk);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tiledarkon").defaultValue), s.tileDarkOn);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tiledarkoff").defaultValue), s.tileDarkOff);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tiledarkonink").defaultValue), s.tileDarkOnInk);
        assertEquals(Rgba.argb(SettingsRegistry.get("qs.tiledarkoffink").defaultValue), s.tileDarkOffInk);
        assertEquals(.25f, Tuning.qsMediaArtOpacity(""), 0f);
        assertEquals(.50f, Tuning.mediaArtOpacity(""), 0f);
    }
}

package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

public class ControlLayoutTest {
    @Test
    public void slidersCoverEveryNumericDomainWithoutInvalidValues() {
        for (SettingSpec s : SettingsRegistry.all()) {
            if (s.type != SettingSpec.Type.INT && s.type != SettingSpec.Type.FLOAT) continue;
            double previous = Double.NEGATIVE_INFINITY;
            for (int p = 0; p <= ControlLayout.steps(s); p++) {
                String value = ControlLayout.at(s, p);
                assertEquals(s.key, value, s.validate(value));
                double n = Double.parseDouble(value);
                assertTrue(s.key, n >= previous);
                previous = n;
            }
            assertEquals(s.key, s.min, Double.parseDouble(ControlLayout.at(s, 0)), .000001);
            assertEquals(
                    s.key,
                    s.max,
                    Double.parseDouble(ControlLayout.at(s, ControlLayout.steps(s))),
                    .000001);
            assertEquals(
                    s.key,
                    Double.parseDouble(s.defaultValue),
                    Double.parseDouble(
                            ControlLayout.at(s, ControlLayout.progress(s, s.defaultValue))),
                    .000001);
        }
    }

    @Test
    public void principalControlsExistAndBelongToSurface() {
        for (String surface : ProductFlags.SURFACES)
            for (String key : ControlLayout.primary(surface)) {
                SettingSpec s = SettingsRegistry.get(key);
                assertNotNull(key, s);
                assertTrue(key, s.surfaceScope.equals("all") || s.surfaceScope.equals(surface));
                assertFalse(s.helperOwned());
                assertNotEquals(SettingSpec.Type.READONLY, s.type);
            }
    }

    @Test
    public void everyControlHasAnEffectGroupAndAccurateApplicationMode() {
        for (SettingSpec s : SettingsRegistry.all()) {
            assertFalse(s.key, ControlLayout.category(s).isEmpty());
            String mode = ControlLayout.application(s);
            if (s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE)
                assertTrue(s.key, mode.contains("recompilar SurfaceFlinger"));
            else if (s.applyMode == SettingSpec.ApplyMode.LIVE && !s.helperOwned())
                assertTrue(s.key, mode.contains("sem reinício"));
        }
    }
}

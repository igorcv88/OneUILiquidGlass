package io.github.igorcv88.oneuiliquidglass.config;

import org.junit.Test;

import static org.junit.Assert.*;

/** New settings must preserve the shipped appearance until explicitly changed. */
public final class IconCustomizationContractTest {
    @Test public void defaultSizesAreIndependent() {
        assertEquals("0.76", SettingsRegistry.get("iconcardsize").defaultValue);
        assertEquals("0.88", SettingsRegistry.get("statusiconsize").defaultValue);
        assertEquals("1", SettingsRegistry.get("iconmode").defaultValue);
    }

    @Test public void samsungStatusBarLimitRemainsNativeByDefault() {
        SettingSpec spec = SettingsRegistry.get("statusmaxicons");
        assertEquals("0", spec.defaultValue);
        assertEquals("0", spec.validate("0"));
        assertEquals("10", spec.validate("10"));
        try {
            spec.validate("11");
            fail("Number greater than the limit must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void customColorsAreOptionalAndValidated() {
        assertEquals("0", SettingsRegistry.get("iconinkmode").defaultValue);
        assertEquals("0", SettingsRegistry.get("statusinkmode").defaultValue);
        assertEquals("ffffff", SettingsRegistry.get("statusfixedink").validate("ffffff"));
        try {
            SettingsRegistry.get("statuslightink").validate("not a color");
            fail("Color must be validated");
        } catch (IllegalArgumentException expected) { }
    }
}

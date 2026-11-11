package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;
import org.junit.Test;

public class ExternalChangesTest {
    private final SettingSpec ramp = SettingsRegistry.get("sf.ramp");

    @Test public void rebootedPropertyWithSameEffectiveValueIsNotAChange() {
        assertFalse(ExternalChanges.conflict(ramp, "1.00", ramp.defaultValue, "",
                ExternalChanges.UNKNOWN_BOOT, ExternalChanges.UNKNOWN_BOOT));
        assertFalse(ExternalChanges.conflict(ramp, "9", "1.00", "1",
                ExternalChanges.UNKNOWN_BOOT, ExternalChanges.UNKNOWN_BOOT));
    }

    @Test public void numericFormattingAndEquivalentDefaultsDoNotConflict() {
        assertFalse(ExternalChanges.conflict(ramp, "1.00", "2", "1.0", 7, 7));
        assertFalse(ExternalChanges.conflict(ramp, "1", "2", "1.000", 7, 7));
        SettingSpec flag = SettingsRegistry.get("sf.kgfixedrim");
        assertFalse(ExternalChanges.conflict(flag, flag.defaultValue, flag.defaultValue, "", 7, 7));
    }

    @Test public void bootBoundaryInvalidatesOnlyOldBaselines() {
        assertFalse(ExternalChanges.conflict(ramp, "1", "2", "3", 7, 8));
        assertTrue(ExternalChanges.conflict(ramp, "1", "2", "3", 8, 8));
        assertTrue(ExternalChanges.conflict(ramp, "1", "2", "3",
                ExternalChanges.UNKNOWN_BOOT, 8));
    }

    @Test public void realTerminalEditRemainsProtected() {
        assertTrue(ExternalChanges.conflict(ramp, "1", "2", "3", 7, 7));
        assertFalse(ExternalChanges.conflict(ramp, null, "2", "3", 7, 7));
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Guards the cross-package field access used by QuickSettingsHooks. */
public final class QsClearVisibilityTest {
    @Test public void clearIsPublicForTheHookPackage() {
        assertEquals(QuickSettingsGlass.color(0x00ffffff),
                QuickSettingsGlass.color(QuickSettingsGlass.CLEAR));
    }
}

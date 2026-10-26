package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class SurfaceContrastTest {
    @Test
    public void shadeContrastIsIndependentOfBothKeyguardSwitches() {
        // Every combination: global + shade glass/contrast + keyguard glass/contrast.
        for (int bits = 0; bits < 32; bits++) {
            boolean global = (bits & 1) != 0, shade = (bits & 2) != 0, tone = (bits & 4) != 0;
            Map<String, Boolean> flags = new HashMap<>();
            flags.put("surface.shade.enabled", shade);
            flags.put("surface.shade.feature.tonemap.enabled", tone);
            flags.put("surface.keyguard.enabled", (bits & 8) != 0);
            flags.put("surface.keyguard.feature.tonemap.enabled", (bits & 16) != 0);
            assertEquals(
                    "combination " + bits,
                    global && shade && tone,
                    ProductFlags.shadeContrastEnabled(
                            global, key -> flags.getOrDefault(key, true)));
        }
    }

    @Test
    public void shelfPolicyNeverReadsKeyguardMaterialFlags() {
        assertTrue(
                ProductFlags.shadeContrastEnabled(
                        true,
                        key -> {
                            assertTrue(
                                    "unexpected surface: " + key, key.startsWith("surface.shade."));
                            return true;
                        }));
    }
}

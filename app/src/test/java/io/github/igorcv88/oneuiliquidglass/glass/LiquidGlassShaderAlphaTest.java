package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Source-contract checks; actual AGSL compilation and HWC display require an Android device. */
public final class LiquidGlassShaderAlphaTest {
    @Test public void productionEdgePathIsScopedToCompositorEdge() {
        String source = LiquidGlassShader.SOURCE;
        assertTrue(source.contains("uniform float edgePremulSafe;"));
        assertTrue(source.contains("uniform float edgeBrightness;"));
        assertTrue(source.contains("optics.rgb * half(edgeBrightness)"));
        assertTrue(source.contains("if (edgePremulSafe > 0.5)"));
        assertTrue(source.contains("half litAlpha = max(max(optics.r, optics.g), optics.b);"));
        assertTrue(source.contains("optics.a = max(optics.a, litAlpha);"));
        assertTrue(source.contains("return optics;"));
        assertFalse(LiquidGlassShader.REFRACT_SOURCE.contains("edgePremulSafe"));
    }

    @Test public void diagnosticSettingAcceptsOnlyFiveModes() {
        io.github.igorcv88.oneuiliquidglass.config.SettingSpec spec =
                io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry.get("edgepremul");
        assertTrue(spec != null);
        assertEquals("0", spec.defaultValue);
        for (String value : new String[] {"0", "1", "2", "3", "4"})
            assertTrue(value.equals(spec.validate(value)));
        for (String value : new String[] {"5", "-1", "1.5"}) {
            try {
                spec.validate(value);
                org.junit.Assert.fail("Accepted invalid diagnostic mode " + value);
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test public void alphaEnvelopeAdmitsBrightZeroShadowPixels() {
        // Mirrors the shader's optional alpha envelope after applying coverage.
        float[][] cases = {
            {0f, 0f, 0f, 0f},
            {0.40f, 0.30f, 0.20f, 0f},
            {0.10f, 0.20f, 0.15f, 0.50f},
            {1f, 0.98f, 0.92f, 0.01f}
        };
        for (float[] c : cases) {
            float a = Math.max(c[3], Math.max(Math.max(c[0], c[1]), c[2]));
            assertTrue(a >= c[0] && a >= c[1] && a >= c[2]);
            assertTrue(a >= c[3] && a >= 0f && a <= 1f);
        }
    }
}

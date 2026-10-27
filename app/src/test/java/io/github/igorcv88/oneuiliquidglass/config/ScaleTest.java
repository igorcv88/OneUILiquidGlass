package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

public class ScaleTest {
    @Test
    public void everySliderPositionIsValidMonotonicAndReachesTheRange() {
        for (Controls.Control c : Controls.all()) {
            SettingSpec s = c.spec();
            if (s.type != SettingSpec.Type.INT && s.type != SettingSpec.Type.FLOAT) continue;
            if (!Controls.editable(c.key)) continue;
            Scale scale = Scale.of(c);
            double previous = Double.NEGATIVE_INFINITY;
            for (int i = 0; i <= scale.steps; i++) {
                String v = scale.value(i);
                assertEquals(c.key, v, s.validate(v));
                double n = Double.parseDouble(v);
                assertTrue(c.key + " at " + i, n >= previous);
                previous = n;
                assertEquals(c.key + " round trip at " + i, i, scale.index(v));
            }
            assertEquals(c.key, s.max, Double.parseDouble(scale.value(scale.steps)), 1e-6);
            assertEquals(c.key, Double.parseDouble(s.defaultValue),
                    Double.parseDouble(scale.value(scale.index(s.defaultValue))), 1e-6);
        }
    }

    @Test
    public void automaticSentinelIsOneDetentWithoutADeadZone() {
        Scale kglens = Scale.of(Controls.get("kglens"));
        assertTrue(kglens.auto);
        assertEquals("-1", kglens.value(0));
        assertEquals("0.1", kglens.value(1));
        Scale kgblur = Scale.of(Controls.get("kgblurradius"));
        assertEquals("-1", kgblur.value(0));
        assertEquals("0", kgblur.value(1));
        assertEquals(401, kgblur.steps); // automatic + 0..400 px
        assertFalse(Scale.of(Controls.get("liveradius")).auto);
    }
}

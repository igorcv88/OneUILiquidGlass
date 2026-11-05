package io.github.igorcv88.oneuiliquidglass.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Slider positions for a numeric control. A control with an automatic sentinel (-1) gets one
 * detent at the start and the valid range after it, so no stretch of the track maps to invalid
 * values (kglens -1 or 0.1-1.2 had a dead zone with a plain min-max mapping).
 */
public final class Scale {
    public final SettingSpec spec;
    public final boolean auto;
    private final boolean nativeArt;
    /** First valid non-sentinel value. */
    public final double low;
    public final int steps;

    private Scale(SettingSpec spec, boolean auto, double low) {
        this.spec = spec;
        this.auto = auto;
        this.nativeArt = spec.key.equals("mediaart");
        this.low = low;
        int span = (int) Math.ceil((spec.max - low) / spec.step - 1e-6);
        steps = Math.max(1, span + (auto || nativeArt ? 1 : 0));
    }

    public static Scale of(Controls.Control c) {
        SettingSpec s = c.spec();
        if (c.key.equals("mediaart")) return new Scale(s, false, .5);
        if (c.autoLabel != null && s.min < 0) {
            double low = c.key.equals("kglens") ? .1 : 0;
            return new Scale(s, true, low);
        }
        return new Scale(s, false, s.min);
    }

    /** Raw validated value at a position 0..steps. */
    public String value(int index) {
        index = Math.max(0, Math.min(steps, index));
        if (nativeArt && index == 0) return spec.validate("0");
        if (auto && index == 0) return spec.validate("-1");
        double n = low + (index - (auto || nativeArt ? 1 : 0)) * spec.step;
        n = Math.min(spec.max, n);
        if (spec.type == SettingSpec.Type.INT) n = Math.rint(n);
        return spec.validate(
                BigDecimal.valueOf(n).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
                        .toPlainString());
    }

    public int index(String raw) {
        double n;
        try {
            n = Double.parseDouble(raw);
        } catch (RuntimeException e) {
            return 0;
        }
        if (nativeArt && n <= 0) return 0;
        if (auto && n < 0) return 0;
        if (n >= spec.max) return steps;
        int i = (int) Math.round((Math.max(low, n) - low) / spec.step) + (auto || nativeArt ? 1 : 0);
        return Math.max(0, Math.min(steps, i));
    }
}

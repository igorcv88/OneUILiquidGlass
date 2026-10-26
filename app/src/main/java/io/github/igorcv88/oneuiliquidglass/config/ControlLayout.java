package io.github.igorcv88.oneuiliquidglass.config;

import java.math.BigDecimal;
import java.util.*;

/** Presentation policy only. Does not change defaults, clamps or renderer behavior. */
public final class ControlLayout {
    private ControlLayout() {}

    public static List<String> primary(String surface) {
        switch (surface) {
            case "keyguard":
                return Arrays.asList("kgblurradius", "kglens", "kgdarkfill", "kgrim", "kgglint");
            case "shade":
                return Arrays.asList("liveshaderadius", "shadelightglass", "veilmax", "shadeglint");
            default:
                return Arrays.asList("liveradius", "sflens", "lightglass", "liverim", "liveglint");
        }
    }

    public static String category(SettingSpec s) {
        if (s.key.startsWith("sf.")) {
            if (s.key.contains("sat")
                    || s.key.contains("tone")
                    || s.key.contains("level")
                    || s.key.contains("cool")
                    || s.key.contains("dim")
                    || s.key.contains("cap")
                    || s.key.contains("lift")) return "Compositor · cor e contraste";
            return "Compositor · refração e shader";
        }
        if (s.group.equals("Captura")) return "Captura e movimento";
        if (s.group.equals("Bordas e luz")) return "Refração, bordas e luz";
        if (s.group.equals("Cor e contraste")) return "Cor e legibilidade";
        if (s.group.equals("Blur e material")) return "Vidro e desfoque";
        return "Comportamento e diagnóstico";
    }

    public static int steps(SettingSpec s) {
        return Math.max(1, (int) Math.ceil((s.max - s.min) / s.step));
    }

    public static String at(SettingSpec s, int progress) {
        double n = Math.min(s.max, Math.max(s.min, s.min + progress * s.step));
        if (s.key.equals("kglens") && n > -1 && n < .1) n = .1;
        if (s.type == SettingSpec.Type.INT) n = Math.rint(n);
        return s.validate(
                BigDecimal.valueOf(n)
                        .setScale(6, java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString());
    }

    public static int progress(SettingSpec s, String value) {
        try {
            return Math.max(
                    0,
                    Math.min(
                            steps(s),
                            (int) Math.round((Double.parseDouble(value) - s.min) / s.step)));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public static String application(SettingSpec s) {
        if (s.helperOwned()) return "Controlado pela injeção";
        switch (s.applyMode) {
            case SF_RECOMPILE:
                return "Manual · recompilar SurfaceFlinger";
            case READONLY:
                return "Somente leitura";
            default:
                return "Ao vivo · sem reinício da SystemUI";
        }
    }
}

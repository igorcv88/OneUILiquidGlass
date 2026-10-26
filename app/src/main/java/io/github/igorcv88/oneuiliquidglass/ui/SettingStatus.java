package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.config.*;

import java.util.Map;

/** Presentation policy never treats an unverified shader or requested flag as effective. */
public final class SettingStatus {
    public static String inactive(SettingSpec s, Map<String, String> values) {
        if (s.dependencies.isEmpty()) return null;
        for (String expression : s.dependencies.split(";")) {
            String[] pair = expression.split("=", 2);
            SettingSpec dependency = SettingsRegistry.get(pair[0]);
            String actual =
                    values.getOrDefault(pair[0], dependency == null ? "" : dependency.defaultValue);
            boolean matches = false;
            for (String option : pair[1].split("\\|")) if (actual.equals(option)) matches = true;
            if (!matches) return "Não se aplica neste modo · requer " + expression;
        }
        return null;
    }

    private SettingStatus() {}
}

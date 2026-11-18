package io.github.igorcv88.oneuiliquidglass.config.state;

import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What the app has confirmed, with no draft: saved intent, observed properties, the compositor's
 * compiled inputs and the master switch. Immutable and free of Android, so the publication plan,
 * the effective state and the edit guard can be tested without a device
 * (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 3).
 */
public final class ConfirmedSnapshot {
    public final Map<String, String> saved, observed;
    /** Inputs of the last confirmed restart (or the attempt awaiting verification); null = unknown. */
    public final Map<String, String> compiled;
    public final boolean enabled, propsAvailable, awaitingVerification;

    public ConfirmedSnapshot(
            Map<String, String> saved,
            Map<String, String> observed,
            Map<String, String> compiled,
            boolean enabled,
            boolean propsAvailable,
            boolean awaitingVerification) {
        this.saved = Collections.unmodifiableMap(new HashMap<>(saved));
        this.observed = Collections.unmodifiableMap(new HashMap<>(observed));
        this.compiled = compiled == null ? null : Collections.unmodifiableMap(new HashMap<>(compiled));
        this.enabled = enabled;
        this.propsAvailable = propsAvailable;
        this.awaitingVerification = awaitingVerification;
    }

    /** The value the readers will use after the next Apply, without drafts; null for an unknown key. */
    public String value(String key) {
        if (key.equals("enabled")) return enabled ? "1" : "0";
        if (ProductFlags.known(key)) {
            String v = saved.get(key);
            return v != null ? v : ProductFlags.defaultEnabled(key) ? "1" : "0";
        }
        SettingSpec spec = SettingsRegistry.get(key);
        if (spec == null) return null;
        if (spec.helperOwned() || spec.type == SettingSpec.Type.READONLY)
            return nonEmpty(observed.get(key), spec.defaultValue);
        String v = saved.get(key);
        if (v != null) return v;
        return nonEmpty(observed.get(key), spec.defaultValue);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}

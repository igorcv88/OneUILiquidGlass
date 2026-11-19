package io.github.igorcv88.oneuiliquidglass.config.state;

import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What the app has confirmed, with no draft: saved intent, observed properties, the evidence of
 * the compositor's last compile, the helper's status and the master switch. Immutable and free of
 * Android, so the publication plan, the effective state and the edit guard can be tested without a
 * device (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 3 and
 * ACTIVATION_AND_BACKUP_CONTRACT.md section 4).
 */
public final class ConfirmedSnapshot {
    public final Map<String, String> saved, observed;
    /** Evidence of the last confirmed compile; {@link CompiledEvidence#none()} when there is none. */
    public final CompiledEvidence compiled;
    /** The helper's last status answer; empty when it was not read. */
    public final Map<String, String> status;
    /** Whether the system properties could be read (an empty map read successfully still counts). */
    public final boolean enabled, propsAvailable, awaitingVerification;
    /** Revision of the saved values (ConfigRepository.generation), so a plan notices a concurrent edit. */
    public final long revision;

    public ConfirmedSnapshot(
            Map<String, String> saved,
            Map<String, String> observed,
            CompiledEvidence compiled,
            Map<String, String> status,
            boolean enabled,
            boolean propsAvailable,
            boolean awaitingVerification,
            long revision) {
        this.saved = Collections.unmodifiableMap(new HashMap<>(saved));
        this.observed = Collections.unmodifiableMap(new HashMap<>(observed));
        this.compiled = compiled == null ? CompiledEvidence.none() : compiled;
        this.status = Collections.unmodifiableMap(new HashMap<>(status));
        this.enabled = enabled;
        this.propsAvailable = propsAvailable;
        this.awaitingVerification = awaitingVerification;
        this.revision = revision;
    }

    /** The same confirmed state with other saved values and master switch (an import, simulated). */
    public ConfirmedSnapshot withSaved(Map<String, String> savedValues, boolean enabledAfter) {
        return new ConfirmedSnapshot(savedValues, observed, compiled, status, enabledAfter,
                propsAvailable, awaitingVerification, revision);
    }

    /**
     * Whether the compositor library is loaded now: the helper's answer when it was read, else the
     * property the helper sets while it is.
     */
    public boolean lensLoaded() {
        if (!status.isEmpty())
            return "1".equals(status.get("sfrefract")) && "1".equals(status.get("sf_loaded"));
        return "1".equals(observed.get("sfrefract"));
    }

    /** The compositor generation the helper reports; empty when unknown. */
    public String generation() {
        String g = status.get("sf_generation");
        return g == null ? "" : g;
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

    /**
     * The values of the next compile: every restart of the app uses the rewrite mode, which loads
     * the lens, so {@code sfrefract} reads 1 whatever the state now. Held in memory only; the
     * property is the helper's and is never written or saved.
     */
    public String nextValue(String key) {
        return "sfrefract".equals(key) ? "1" : value(key);
    }

    public Function<String, String> nextReader() {
        return this::nextValue;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}

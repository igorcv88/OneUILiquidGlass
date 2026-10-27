package io.github.igorcv88.oneuiliquidglass.live;

import android.content.SharedPreferences;

import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;

import java.math.BigDecimal;
import java.util.*;

/**
 * Desired vs. compiled compositor parameters. The library reads debug.oulg.sf.* only when a
 * shader compiles, so a saved value is pending until a restart that happened after the value was
 * written, with the library loaded again. Nothing here claims more than that evidence.
 */
public final class CompositorValues {
    public enum Outcome {
        /** No restart was requested from the app. */
        NONE,
        /** Restart requested; the helper has not been asked since. */
        UNVERIFIED,
        /** Library loaded after the restart and every written value reads back. */
        CONFIRMED,
        /** The library is not mapped by the running compositor. */
        NOT_LOADED,
        /** Loaded, but a value differs from what was written (changed elsewhere since). */
        CHANGED
    }

    private CompositorValues() {}

    /** Keys the app may write through the helper. */
    public static boolean writable(String key) {
        return key.startsWith("sf.") && SfLive.WRITABLE.contains(key.substring(3));
    }

    /** Numeric equality where an empty observed value means the library default. */
    static boolean same(SettingSpec spec, String a, String b) {
        String x = a == null || a.isEmpty() ? spec.defaultValue : a;
        String y = b == null || b.isEmpty() ? spec.defaultValue : b;
        try {
            return new BigDecimal(x.trim()).compareTo(new BigDecimal(y.trim())) == 0;
        } catch (NumberFormatException e) {
            return x.trim().equals(y.trim());
        }
    }

    /**
     * Saved compositor values the running compositor does not have yet: saved and different from
     * the property it would read (empty = library default).
     */
    public static Map<String, String> pending(
            Map<String, String> saved, Map<String, String> observed) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            if (!writable(e.getKey())) continue;
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null) continue;
            if (!same(spec, e.getValue(), observed.get(e.getKey())))
                out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** commit(): the restart that follows kills this process before an apply() would land. */
    @android.annotation.SuppressLint("ApplySharedPref")
    public static void recordAttempt(SharedPreferences prefs, Map<String, String> values, long at) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet())
            encoded.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        prefs.edit()
                .putLong("restartRequestedAt", at)
                .putString("restartValues", encoded.toString())
                .remove("verifiedAt")
                .commit();
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    public static void clearAttempt(SharedPreferences prefs) {
        prefs.edit().remove("restartRequestedAt").remove("restartValues").commit();
    }

    public static Map<String, String> attemptValues(SharedPreferences prefs) {
        Map<String, String> out = new TreeMap<>();
        for (String line : prefs.getString("restartValues", "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }

    /**
     * What the helper's status and the observed properties say about the last restart the app
     * requested. {@code status} empty means the helper was not asked yet.
     */
    public static Outcome verify(
            long requestedAt,
            Map<String, String> written,
            Map<String, String> status,
            Map<String, String> observed) {
        if (requestedAt <= 0) return Outcome.NONE;
        if (status.isEmpty() || !"1".equals(status.get("helper"))) return Outcome.UNVERIFIED;
        if (!"1".equals(status.get("sf_loaded")) || !"1".equals(status.get("sfrefract")))
            return Outcome.NOT_LOADED;
        for (Map.Entry<String, String> e : written.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null || !same(spec, e.getValue(), observed.get(e.getKey())))
                return Outcome.CHANGED;
        }
        return Outcome.CONFIRMED;
    }
}

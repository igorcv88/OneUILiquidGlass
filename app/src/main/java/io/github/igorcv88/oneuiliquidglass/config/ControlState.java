package io.github.igorcv88.oneuiliquidglass.config;

import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;

import java.util.*;

/**
 * One immutable view of every value the UI and the preview use, keeping apart:
 *
 * <ul>
 *   <li><b>saved</b>: what the app stores (desired; registry keys and product flags);
 *   <li><b>observed</b>: debug.oulg.* properties read from the phone (Terminal, helper);
 *   <li><b>draft</b>: a value being dragged, not saved yet;
 *   <li><b>device</b>: what SystemUI or the compositor reads now, by the same precedence they
 *       use (ConfigClient override, then the property, then the code default). Compositor values
 *       are read only at shader compilation, so their device value is the property.
 * </ul>
 *
 * The preview draws {@link #shown}; applicability uses {@link #confirmed} (no drafts), and the
 * helper-owned mode switch is set only by the injector.
 */
public final class ControlState {
    public final Map<String, String> saved, observed, drafts;
    public final boolean enabled;

    public ControlState(
            Map<String, String> saved,
            Map<String, String> observed,
            Map<String, String> drafts,
            boolean enabled) {
        this.saved = Collections.unmodifiableMap(new HashMap<>(saved));
        this.observed = Collections.unmodifiableMap(new HashMap<>(observed));
        this.drafts = Collections.unmodifiableMap(new HashMap<>(drafts));
        this.enabled = enabled;
    }

    public ControlState withDraft(String key, String value) {
        Map<String, String> next = new HashMap<>(drafts);
        if (value == null) next.remove(key);
        else next.put(key, value);
        return new ControlState(saved, observed, next, enabled);
    }

    public ControlState withSaved(Map<String, String> replace) {
        Map<String, String> next = new HashMap<>(saved);
        next.putAll(replace);
        return new ControlState(next, observed, drafts, enabled);
    }

    public ControlState withEnabled(boolean on) {
        return new ControlState(saved, observed, drafts, on);
    }

    private static String def(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        return s == null ? null : s.defaultValue;
    }

    private static boolean helperOwned(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        return s != null && (s.helperOwned() || s.type == SettingSpec.Type.READONLY);
    }

    /** What the device reads now for a registry key. */
    public String device(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        if (s == null) return null;
        if (s.applyMode == SettingSpec.ApplyMode.LIVE && !helperOwned(key)) {
            String v = saved.get(key);
            if (v != null) return v;
        }
        String o = observed.get(key);
        return o == null || o.isEmpty() ? s.defaultValue : o;
    }

    /** The value the person sees and the preview draws: draft, saved, then device. */
    public String shown(String key) {
        return resolve(key, drafts);
    }

    /**
     * The value the rules, the guard and the publication see: {@link #shown} without drafts.
     * A value being dragged is a preview, never an input to what is applicable or published.
     */
    public String confirmed(String key) {
        return resolve(key, Collections.<String, String>emptyMap());
    }

    private String resolve(String key, Map<String, String> draftValues) {
        if (key.equals("enabled")) return enabled ? "1" : "0";
        if (ProductFlags.known(key)) {
            String d = draftValues.get(key), v = d != null ? d : saved.get(key);
            return v == null ? (ProductFlags.defaultEnabled(key) ? "1" : "0") : v;
        }
        if (helperOwned(key)) return device(key);
        String d = draftValues.get(key);
        if (d != null) return d;
        String v = saved.get(key);
        if (v != null) return v;
        return device(key);
    }

    /** Compositor value saved but not what the running compositor would compile with. */
    public boolean pendingRestart(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        if (s == null || s.applyMode != SettingSpec.ApplyMode.SF_RECOMPILE) return false;
        String v = saved.get(key);
        if (v == null) return false;
        String o = observed.get(key);
        String compiled = o == null || o.isEmpty() ? s.defaultValue : o;
        try {
            return Double.parseDouble(v) != Double.parseDouble(compiled);
        } catch (NumberFormatException e) {
            return !v.equals(compiled);
        }
    }

    /** Whether the shown value differs from the code default. */
    public boolean changed(String key) {
        String d = def(key), v = shown(key);
        if (d == null || v == null) return false;
        try {
            return Double.parseDouble(d) != Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return !d.equalsIgnoreCase(v);
        }
    }

    /** The rules as the confirmed values decide them: drafts never reach this (no editing, publishing or guarding). */
    public Applicability applicability() {
        return new Applicability(this::confirmed);
    }

    /**
     * The rules with the lens intent in mind: {@code sf.*} keys are judged on the next compile
     * (virtual {@code sfrefract=1}, held in memory only) when the person means to use the lens.
     * Still no drafts.
     */
    public Applicability applicability(boolean lensIntent) {
        Applicability now = applicability();
        return lensIntent ? now.withNext(nextApplicability()) : now;
    }

    /** The rules of the next compile: every restart of the app loads the lens. */
    public Applicability nextApplicability() {
        return new Applicability(k -> "sfrefract".equals(k) ? "1" : confirmed(k));
    }

    /** The rules as the preview draws them, drafts included. Only the preview may call this. */
    public Applicability previewApplicability() {
        return new Applicability(this::shown);
    }

    /** The draft-free snapshot the publication plan and the effective state work from. */
    public ConfirmedSnapshot snapshot(
            CompiledEvidence compiled, Map<String, String> status, boolean awaitingVerification, long revision) {
        return new ConfirmedSnapshot(saved, observed, compiled, status, enabled,
                SystemPropertyReader.available(), awaitingVerification, revision);
    }

    /** Every registry key and product flag, as the preview reads them. */
    public Map<String, String> shownMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (SettingSpec s : SettingsRegistry.all()) m.put(s.key, shown(s.key));
        for (String surface : ProductFlags.SURFACES) {
            String base = "surface." + surface;
            m.put(base + ".enabled", shown(base + ".enabled"));
            for (String f : ProductFlags.FEATURES) {
                String k = base + ".feature." + f + ".enabled";
                m.put(k, shown(k));
            }
        }
        for (String k : ProductFlags.OPT_IN) m.put(k, shown(k));
        m.put("enabled", enabled ? "1" : "0");
        return m;
    }
}

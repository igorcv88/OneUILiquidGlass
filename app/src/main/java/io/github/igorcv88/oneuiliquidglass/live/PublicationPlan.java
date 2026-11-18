package io.github.igorcv88.oneuiliquidglass.live;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What the next Apply writes and what it only has to recompile, decided from the confirmed
 * snapshot alone (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 4). Pure Java: no draft,
 * no Android. A saved value whose control is inactive on the target configuration is never
 * written; it is kept (deferred) and returns when the rule does.
 */
public final class PublicationPlan {
    /** The target is re-evaluated until the write set stops changing; more rounds mean a contradiction. */
    private static final int MAX_ROUNDS = 4;

    /** Compositor properties to set: saved intent that differs from what the library reads. */
    public final Map<String, String> writes;
    /** Properties read now that differ from the compiled inputs, for keys that matter. */
    public final Map<String, String> activeDrift;
    /** Drift of keys the current configuration ignores: reported, never queued. */
    public final Set<String> inactiveDrift;
    /** Saved values held back because their control is inactive. */
    public final Set<String> deferred;
    /** Keys the app does not own: a property is set and no value is saved. */
    public final Set<String> external;
    public final boolean unknownBaseline;
    public final String fingerprint;

    private PublicationPlan(
            Map<String, String> writes,
            Map<String, String> activeDrift,
            Set<String> inactiveDrift,
            Set<String> deferred,
            Set<String> external,
            boolean unknownBaseline) {
        this.writes = Collections.unmodifiableMap(new TreeMap<>(writes));
        this.activeDrift = Collections.unmodifiableMap(new TreeMap<>(activeDrift));
        this.inactiveDrift = Collections.unmodifiableSet(new TreeSet<>(inactiveDrift));
        this.deferred = Collections.unmodifiableSet(new TreeSet<>(deferred));
        this.external = Collections.unmodifiableSet(new TreeSet<>(external));
        this.unknownBaseline = unknownBaseline;
        this.fingerprint = fingerprint(this.writes, this.activeDrift);
    }

    public boolean needsCompile() {
        return !writes.isEmpty() || !activeDrift.isEmpty();
    }

    /** What the UI counts as waiting to apply: writes and active drift (disjoint). */
    public Map<String, String> listed() {
        Map<String, String> all = new TreeMap<>(writes);
        all.putAll(activeDrift);
        return Collections.unmodifiableMap(all);
    }

    /**
     * @param rules builds the applicability rules for a value reader; production passes
     *     Applicability::new
     */
    public static PublicationPlan of(
            ConfirmedSnapshot s, Function<Function<String, String>, Applicability> rules)
            throws PlanContradiction {
        Map<String, String> writes = new TreeMap<>();
        Map<String, String> previous = writes;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Applicability applicability = rules.apply(target(s, writes));
            Map<String, String> next = writesFor(s, applicability);
            if (next.equals(writes)) return build(s, writes, applicability);
            previous = writes;
            writes = next;
        }
        throw new PlanContradiction(oscillating(previous, writes));
    }

    /** Keys present in only one of the last two write sets, or with different values in them. */
    private static Set<String> oscillating(Map<String, String> a, Map<String, String> b) {
        Set<String> keys = new TreeSet<>();
        for (Map.Entry<String, String> e : a.entrySet())
            if (!e.getValue().equals(b.get(e.getKey()))) keys.add(e.getKey());
        for (Map.Entry<String, String> e : b.entrySet())
            if (!e.getValue().equals(a.get(e.getKey()))) keys.add(e.getKey());
        return keys;
    }

    /** The configuration the readers will have: observed, the provisional writes and the live saved values. */
    private static Function<String, String> target(ConfirmedSnapshot s, Map<String, String> writes) {
        return key -> {
            String written = writes.get(key);
            if (written != null) return written;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null) return s.value(key);
            boolean live = spec.applyMode == SettingSpec.ApplyMode.LIVE && !spec.helperOwned();
            String saved = live ? s.saved.get(key) : null;
            if (saved != null) return saved;
            String observed = s.observed.get(key);
            return observed == null || observed.isEmpty() ? spec.defaultValue : observed;
        };
    }

    private static Map<String, String> writesFor(ConfirmedSnapshot s, Applicability rules) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : s.saved.entrySet()) {
            String key = e.getKey();
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null || !CompositorValues.writable(key)) continue;
            if (!rules.rule(key).usable()) continue;
            if (!CompositorValues.same(spec, e.getValue(), s.observed.get(key))) out.put(key, e.getValue());
        }
        return out;
    }

    private static PublicationPlan build(
            ConfirmedSnapshot s, Map<String, String> writes, Applicability rules) {
        Map<String, String> active = new TreeMap<>();
        Set<String> inactive = new TreeSet<>(), deferred = new TreeSet<>(), external = new TreeSet<>();
        if (s.compiled != null) {
            for (String shortKey : SfLive.WRITABLE) {
                String key = "sf." + shortKey;
                SettingSpec spec = SettingsRegistry.get(key);
                // A baseline from before a key became writable says nothing about it.
                if (spec == null || !s.compiled.containsKey(key) || writes.containsKey(key)) continue;
                String observed = s.observed.get(key);
                if (CompositorValues.same(spec, observed, s.compiled.get(key))) continue;
                if (rules.rule(key).kind == Applicability.Kind.INACTIVE) inactive.add(key);
                else active.put(key, observed == null || observed.isEmpty() ? spec.defaultValue : observed);
            }
        }
        for (Map.Entry<String, String> e : s.saved.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null || !CompositorValues.writable(e.getKey())) continue;
            if (rules.rule(e.getKey()).kind == Applicability.Kind.INACTIVE
                    && !CompositorValues.same(spec, e.getValue(), s.observed.get(e.getKey())))
                deferred.add(e.getKey());
        }
        for (Map.Entry<String, String> e : s.observed.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null || spec.helperOwned() || spec.type == SettingSpec.Type.READONLY) continue;
            if (!e.getValue().isEmpty() && !s.saved.containsKey(e.getKey())) external.add(e.getKey());
        }
        return new PublicationPlan(writes, active, inactive, deferred, external, s.compiled == null);
    }

    private static String fingerprint(Map<String, String> writes, Map<String, String> drift) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : writes.entrySet())
            text.append(e.getKey()).append('=').append(e.getValue()).append(',');
        text.append('|');
        for (Map.Entry<String, String> e : drift.entrySet())
            text.append(e.getKey()).append('=').append(e.getValue()).append(',');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

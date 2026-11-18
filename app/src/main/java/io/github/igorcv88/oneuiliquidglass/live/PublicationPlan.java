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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What the next Apply writes, decided from the confirmed snapshot alone (docs/OULG_UI_PLAN/
 * ACTIVATION_AND_BACKUP_CONTRACT.md section 4). Pure Java: no draft, no Android.
 *
 * <p>The plan is the saved intent resolved for the configuration the next compile will have: the
 * app's restart always loads the lens, so the rules are evaluated with a virtual
 * {@code sfrefract=1} that is never written or saved. A saved value the library would read there is
 * written when the property it would read differs (an absent property counts as the library default);
 * a value another configuration ignores stays saved and is not published (dormant). Properties lost
 * with a reboot are restored from the saved intent, never reconciled to defaults, and a
 * compile baseline of another system session is history, not a difference.
 */
public final class PublicationPlan {
    /** The target is re-evaluated until the write set stops changing; more rounds mean a contradiction. */
    private static final int MAX_ROUNDS = 4;

    /** Why a property is written. */
    public enum WriteReason {
        /** The system lost a saved value (reboot, reset): put it back. */
        RESTORE,
        /** The system has another value now: the saved one replaces it. */
        CHANGE
    }

    /** Why a compiled input differs from what the next compile will read. */
    public enum DriftReason {
        /** The property was changed outside the app. */
        EXTERNAL,
        /** The property is gone since the compile. */
        LOST
    }

    public static final class Write {
        public final String value;
        public final WriteReason reason;

        Write(String value, WriteReason reason) {
            this.value = value;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Write && ((Write) o).value.equals(value) && ((Write) o).reason == reason;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, reason);
        }

        @Override
        public String toString() {
            return value + " (" + reason + ")";
        }
    }

    public static final class Drift {
        /** What the next compile will read. */
        public final String value;
        public final DriftReason reason;

        Drift(String value, DriftReason reason) {
            this.value = value;
            this.reason = reason;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Drift && ((Drift) o).value.equals(value) && ((Drift) o).reason == reason;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, reason);
        }

        @Override
        public String toString() {
            return value + " (" + reason + ")";
        }
    }

    /** Compositor properties to set: saved intent that differs from what the library reads. */
    public final Map<String, Write> writes;
    /** Properties the next compile would read differently from the last compile of this very process. */
    public final Map<String, Drift> drift;
    /** Saved values held back because their control is inactive in the next configuration. */
    public final Set<String> dormant;
    /** Keys the app does not own: a property is set and no value is saved. */
    public final Set<String> external;
    /** Earlier-session values nobody holds now (not saved, not set): offered for adoption, never written. */
    public final Set<String> lostExternal;
    /** The lens is not loaded: the restart is an activation. */
    public final boolean activation;
    /** Nothing to write, but the compile cannot be confirmed: offer a verification restart. */
    public final boolean verifyOnly;
    /** The properties could not be read: nothing can be planned safely. */
    public final boolean blocked;
    /** No compile was ever confirmed. */
    public final boolean unknownBaseline;
    public final CompiledEvidence.Freshness evidence;
    public final String fingerprint;

    private PublicationPlan(
            Map<String, Write> writes,
            Map<String, Drift> drift,
            Set<String> dormant,
            Set<String> external,
            Set<String> lostExternal,
            boolean activation,
            boolean verifyOnly,
            boolean blocked,
            ConfirmedSnapshot s) {
        this.writes = Collections.unmodifiableMap(new TreeMap<>(writes));
        this.drift = Collections.unmodifiableMap(new TreeMap<>(drift));
        this.dormant = Collections.unmodifiableSet(new TreeSet<>(dormant));
        this.external = Collections.unmodifiableSet(new TreeSet<>(external));
        this.lostExternal = Collections.unmodifiableSet(new TreeSet<>(lostExternal));
        this.activation = activation;
        this.verifyOnly = verifyOnly;
        this.blocked = blocked;
        this.evidence = s.compiled.freshness;
        this.unknownBaseline = s.compiled.freshness == CompiledEvidence.Freshness.NONE;
        this.fingerprint = fingerprint(this, s);
    }

    /** A restart is needed: to activate the lens, to write something or to compile a difference. */
    public boolean needsRestart() {
        return !blocked && (activation || !writes.isEmpty() || !drift.isEmpty());
    }

    /** The properties to set, as the helper takes them. */
    public Map<String, String> writeValues() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Write> e : writes.entrySet()) out.put(e.getKey(), e.getValue().value);
        return Collections.unmodifiableMap(out);
    }

    /** What the next compile would read for each drifted key. */
    public Map<String, String> driftValues() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Drift> e : drift.entrySet()) out.put(e.getKey(), e.getValue().value);
        return Collections.unmodifiableMap(out);
    }

    /** What the UI counts as waiting to apply: writes and drift (disjoint). */
    public Map<String, String> listed() {
        Map<String, String> all = new TreeMap<>(writeValues());
        for (Map.Entry<String, Drift> e : drift.entrySet()) all.put(e.getKey(), e.getValue().value);
        return Collections.unmodifiableMap(all);
    }

    /**
     * @param rules builds the applicability rules for a value reader; production passes
     *     Applicability::new
     */
    public static PublicationPlan of(
            ConfirmedSnapshot s, Function<Function<String, String>, Applicability> rules)
            throws PlanContradiction {
        if (!s.propsAvailable) return blocked(s);
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

    private static PublicationPlan blocked(ConfirmedSnapshot s) {
        Set<String> none = Collections.emptySet();
        return new PublicationPlan(Collections.<String, Write>emptyMap(), Collections.<String, Drift>emptyMap(),
                none, none, none, false, false, true, s);
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

    /**
     * The configuration the next compile will have: observed, the provisional writes, the live saved
     * values and the virtual lens. Rules are evaluated here, never on the rendering state now.
     */
    private static Function<String, String> target(ConfirmedSnapshot s, Map<String, String> writes) {
        return key -> {
            if ("sfrefract".equals(key)) return "1";
            String written = writes.get(key);
            if (written != null) return written;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null) return s.value(key);
            boolean live = spec.applyMode == SettingSpec.ApplyMode.LIVE && !spec.helperOwned();
            String saved = live ? s.saved.get(key) : null;
            if (saved != null) return saved;
            return presentOr(s.observed.get(key), spec.defaultValue);
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
            ConfirmedSnapshot s, Map<String, String> values, Applicability rules) {
        Map<String, Write> writes = new TreeMap<>();
        for (Map.Entry<String, String> e : values.entrySet())
            writes.put(e.getKey(), new Write(e.getValue(), present(s.observed.get(e.getKey()))
                    ? WriteReason.CHANGE : WriteReason.RESTORE));
        Map<String, Drift> drift = new TreeMap<>();
        Set<String> lost = new TreeSet<>();
        if (s.compiled.current()) driftAgainst(s, values, rules, drift);
        else lostSinceAnotherSession(s, lost);
        boolean activation = !s.lensLoaded();
        boolean needs = activation || !writes.isEmpty() || !drift.isEmpty();
        boolean verifyOnly = !needs && s.lensLoaded() && !s.compiled.current() && !s.awaitingVerification;
        return new PublicationPlan(writes, drift, dormantKeys(s, rules), externalKeys(s), lost,
                activation, verifyOnly, false, s);
    }

    private static Set<String> dormantKeys(ConfirmedSnapshot s, Applicability rules) {
        Set<String> dormant = new TreeSet<>();
        for (Map.Entry<String, String> e : s.saved.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null || !CompositorValues.writable(e.getKey())) continue;
            if (!rules.rule(e.getKey()).usable()
                    && !CompositorValues.same(spec, e.getValue(), s.observed.get(e.getKey())))
                dormant.add(e.getKey());
        }
        return dormant;
    }

    private static Set<String> externalKeys(ConfirmedSnapshot s) {
        Set<String> external = new TreeSet<>();
        for (Map.Entry<String, String> e : s.observed.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            if (spec == null || spec.helperOwned() || spec.type == SettingSpec.Type.READONLY) continue;
            if (!e.getValue().isEmpty() && !s.saved.containsKey(e.getKey())) external.add(e.getKey());
        }
        return external;
    }

    /** Only against a compile of this very compositor process: what the next compile would read differently. */
    private static void driftAgainst(
            ConfirmedSnapshot s, Map<String, String> writes, Applicability rules, Map<String, Drift> drift) {
        for (String shortKey : SfLive.WRITABLE) {
            String key = "sf." + shortKey;
            SettingSpec spec = SettingsRegistry.get(key);
            // A baseline from before a key became writable says nothing about it.
            if (spec == null || !s.compiled.inputs.containsKey(key) || writes.containsKey(key)) continue;
            if (rules.rule(key).kind == Applicability.Kind.INACTIVE) continue;
            String observed = s.observed.get(key), saved = s.saved.get(key);
            String target = saved != null && rules.rule(key).usable() ? saved : presentOr(observed, spec.defaultValue);
            if (CompositorValues.same(spec, target, s.compiled.inputs.get(key))) continue;
            drift.put(key, new Drift(target, present(observed) ? DriftReason.EXTERNAL : DriftReason.LOST));
        }
    }

    /**
     * Values an earlier system session compiled that nobody holds now: not saved, not set, not the
     * default. They are offered for adoption by the person, never written on their own.
     */
    private static void lostSinceAnotherSession(ConfirmedSnapshot s, Set<String> lost) {
        for (String shortKey : SfLive.WRITABLE) {
            String key = "sf." + shortKey;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null || !s.compiled.inputs.containsKey(key)) continue;
            if (s.saved.containsKey(key) || present(s.observed.get(key))) continue;
            if (!CompositorValues.same(spec, s.compiled.inputs.get(key), spec.defaultValue)) lost.add(key);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    private static String presentOr(String value, String fallback) {
        return present(value) ? value : fallback;
    }

    private static String fingerprint(PublicationPlan plan, ConfirmedSnapshot s) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Write> e : plan.writes.entrySet())
            text.append(e.getKey()).append('=').append(e.getValue()).append(',');
        text.append('|');
        for (Map.Entry<String, Drift> e : plan.drift.entrySet())
            text.append(e.getKey()).append('=').append(e.getValue()).append(',');
        text.append('|').append(plan.activation).append(plan.verifyOnly).append(plan.blocked)
                .append('|').append(s.generation()).append('|').append(s.revision).append('|');
        // What the compile will read: a Terminal change between the preview and the tap is a new plan.
        for (String shortKey : SfLive.WRITABLE) {
            String observed = s.observed.get("sf." + shortKey);
            if (observed != null && !observed.isEmpty()) text.append(shortKey).append('=').append(observed).append(',');
        }
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

package io.github.igorcv88.oneuiliquidglass.config;

import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.live.PlanContradiction;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;

import java.util.*;

/**
 * What importing a backup, using a saved style or undoing would do, decided once and applied as
 * decided (docs/OULG_UI_PLAN/ACTIVATION_AND_BACKUP_CONTRACT.md section 6.2). The review screen
 * renders this object and the confirmation writes {@link #result} in one transaction, so what was
 * shown is what is saved. Pure Java; nothing is published or restarted by an import.
 */
public final class ImportPlan {
    public enum Mode { REPLACE, MERGE }

    /** One key whose saved value changes: {@code from}/{@code to} are null where nothing is saved. */
    public static final class Change {
        public final String key;
        public final String from, to;

        Change(String key, String from, String to) {
            this.key = key;
            this.from = from;
            this.to = to;
        }
    }

    /** Every override and flag that will be saved; the single source of the confirmation. */
    public final Map<String, String> result;
    public final boolean enabled, enabledChanges;
    /** Saved on both sides with another value. */
    public final List<Change> changed;
    /** Not saved now, saved after. */
    public final List<Change> added;
    /**
     * Saved now and absent from the file: the control goes back to its default ({@code to}), which
     * {@link #result} saves explicitly so the next Apply publishes it (never released to the Terminal).
     */
    public final List<Change> removed;
    /** Saved on both sides with the same value. */
    public final List<String> kept;
    /** Saved after, but inactive in the next configuration: stored, not published. */
    public final List<String> dormant;
    /** Keys of the file this version does not know or does not import. */
    public final List<String> ignored;
    /** The import changes what the next Apply has to publish. */
    public final boolean needsLens;
    /** The file's defaults differ from this version's for some key (the option is meaningful). */
    public final boolean hasBackupDefaults;
    /** The file carries values the Terminal had set. */
    public final boolean hasTerminal;
    public final boolean diagnosticPresent;
    public final Mode mode;

    private ImportPlan(Mode mode, Map<String, String> result, Set<String> restored, boolean enabled,
            ConfirmedSnapshot current, PresetCodec.Parsed parsed, boolean hasBackupDefaults, boolean needsLens) {
        this.mode = mode;
        this.result = Collections.unmodifiableMap(new LinkedHashMap<>(result));
        this.enabled = enabled;
        this.enabledChanges = enabled != current.enabled;
        List<Change> changed = new ArrayList<>(), added = new ArrayList<>(), removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(result).entrySet()) {
            String before = current.saved.get(e.getKey());
            if (restored.contains(e.getKey())) {
                // Removed by the file: saved explicitly at its default, so the next Apply publishes it.
                if (sameValue(e.getKey(), before, e.getValue())) kept.add(e.getKey());
                else removed.add(new Change(e.getKey(), before, e.getValue()));
            } else if (before == null) added.add(new Change(e.getKey(), null, e.getValue()));
            else if (sameValue(e.getKey(), before, e.getValue())) kept.add(e.getKey());
            else changed.add(new Change(e.getKey(), before, e.getValue()));
        }
        for (Map.Entry<String, String> e : new TreeMap<>(current.saved).entrySet())
            if (!result.containsKey(e.getKey()))
                removed.add(new Change(e.getKey(), e.getValue(), defaultOf(e.getKey())));
        this.changed = Collections.unmodifiableList(changed);
        this.added = Collections.unmodifiableList(added);
        this.removed = Collections.unmodifiableList(removed);
        this.kept = Collections.unmodifiableList(kept);
        this.dormant = Collections.unmodifiableList(dormantKeys(current.withSaved(result, enabled)));
        this.ignored = Collections.unmodifiableList(new ArrayList<>(parsed.unknown));
        this.needsLens = needsLens;
        this.hasBackupDefaults = hasBackupDefaults;
        this.hasTerminal = !parsed.terminal.isEmpty();
        this.diagnosticPresent = parsed.diagnosticPresent;
    }

    /** Whether the import leaves the saved values exactly as they are. */
    public boolean noChanges() {
        return changed.isEmpty() && added.isEmpty() && removed.isEmpty() && !enabledChanges;
    }

    /**
     * @param current what is saved now (overrides, flags, master switch) with the system around it
     * @param useBackupDefaults keep the file's defaults where they differ from this version's
     * @param adoptTerminal save the values the Terminal had set, for keys the file does not own
     */
    public static ImportPlan of(ConfirmedSnapshot current, PresetCodec.Parsed parsed, Mode mode,
            boolean useBackupDefaults, boolean adoptTerminal) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mode == Mode.MERGE) result.putAll(current.saved);
        result.putAll(parsed.values);
        boolean differing = false;
        for (Map.Entry<String, String> e : parsed.implicitDefaults.entrySet()) {
            if (parsed.values.containsKey(e.getKey())) continue;
            boolean differs = !sameValue(e.getKey(), e.getValue(), defaultOf(e.getKey()));
            differing |= differs;
            if (useBackupDefaults && differs) result.put(e.getKey(), e.getValue());
        }
        if (adoptTerminal)
            for (Map.Entry<String, String> e : parsed.terminal.entrySet())
                if (!parsed.values.containsKey(e.getKey())) result.put(e.getKey(), e.getValue());
        Set<String> restored = restoreDefaults(current, result);
        ConfirmedSnapshot after = current.withSaved(result, parsed.enabled);
        return new ImportPlan(mode, result, restored, parsed.enabled, current, parsed, differing,
                publishChanges(current, after));
    }

    /** Undo as a review: the snapshot replaces what is saved, like a style in REPLACE mode. */
    public static ImportPlan ofSnapshot(ConfirmedSnapshot current, Map<String, String> overrides, boolean enabled) {
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.values.putAll(overrides);
        parsed.enabled = enabled;
        return of(current, parsed, Mode.REPLACE, false, false);
    }

    private static boolean publishChanges(ConfirmedSnapshot before, ConfirmedSnapshot after) {
        try {
            PublicationPlan a = PublicationPlan.of(before, Applicability::new);
            PublicationPlan b = PublicationPlan.of(after, Applicability::new);
            return !a.writeValues().equals(b.writeValues()) || !a.listed().equals(b.listed());
        } catch (PlanContradiction e) {
            return true;
        }
    }

    private static List<String> dormantKeys(ConfirmedSnapshot after) {
        Applicability now = new Applicability(after::value), next = new Applicability(after.nextReader());
        List<String> out = new ArrayList<>();
        for (String key : new TreeSet<>(after.saved.keySet())) {
            if (SettingsRegistry.get(key) == null) continue;
            Applicability.Result rule = key.startsWith("sf.") ? next.rule(key) : now.rule(key);
            if (rule.kind == Applicability.Kind.INACTIVE) out.add(key);
        }
        return out;
    }

    /**
     * A key saved now that the result drops goes back to its default, as the review says. Dropping
     * the override would not do that: a property the app published (or a Terminal value) would stay
     * in force and read as external. So the default is saved explicitly and the next Apply publishes
     * it. Handing a key to the Terminal is another, explicit action
     * ({@link ConfigRepository#releaseToExternal}); an import, a style or Undo never does it.
     *
     * @return the keys added to {@code result} at their default
     */
    private static Set<String> restoreDefaults(ConfirmedSnapshot current, Map<String, String> result) {
        Set<String> restored = new TreeSet<>();
        for (String key : new TreeSet<>(current.saved.keySet())) {
            if (result.containsKey(key)) continue;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec != null && (spec.helperOwned() || spec.type == SettingSpec.Type.READONLY)) continue;
            String def = defaultOf(key);
            if (def == null) continue;
            result.put(key, def);
            restored.add(key);
        }
        return restored;
    }

    private static String defaultOf(String key) {
        SettingSpec spec = SettingsRegistry.get(key);
        if (spec != null) return spec.defaultValue;
        return ProductFlags.known(key) ? (ProductFlags.defaultEnabled(key) ? "1" : "0") : null;
    }

    /** Numeric equality for numbers (1 equals 1.00), exact for text and colors. */
    private static boolean sameValue(String key, String a, String b) {
        SettingSpec spec = SettingsRegistry.get(key);
        return spec == null ? Objects.equals(a, b) : ExternalChanges.same(spec, a, b);
    }
}

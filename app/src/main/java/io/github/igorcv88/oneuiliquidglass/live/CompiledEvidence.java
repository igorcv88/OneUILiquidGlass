package io.github.igorcv88.oneuiliquidglass.live;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What the app can claim about the inputs the compositor was last compiled with
 * (docs/OULG_UI_PLAN/ACTIVATION_AND_BACKUP_CONTRACT.md section 3). A recorded baseline is evidence
 * of the running compositor only while the lens is loaded and the baseline belongs to the
 * compositor generation the helper reports now; anything else is history of another system
 * session, shown as such and never used to compute a difference.
 */
public final class CompiledEvidence {
    public enum Freshness {
        /** Same generation as the loaded compositor: usable for differences. */
        CURRENT,
        /** A baseline exists but belongs to another generation, or the lens/status is not confirmed. */
        HISTORICAL,
        /** No baseline was ever confirmed. */
        NONE
    }

    private static final CompiledEvidence ABSENT =
            new CompiledEvidence(Collections.<String, String>emptyMap(), "", 0, Freshness.NONE);

    /** Inputs of the last confirmed compile; empty for NONE. */
    public final Map<String, String> inputs;
    /** The compositor generation those inputs were confirmed for; empty for NONE. */
    public final String generation;
    /** When the restart that produced them was requested (ms); 0 for NONE. */
    public final long at;
    public final Freshness freshness;

    private CompiledEvidence(Map<String, String> inputs, String generation, long at, Freshness freshness) {
        this.inputs = Collections.unmodifiableMap(new HashMap<>(inputs));
        this.generation = generation == null ? "" : generation;
        this.at = at;
        this.freshness = freshness;
    }

    public static CompiledEvidence none() {
        return ABSENT;
    }

    /**
     * @param inputs the confirmed compile's inputs; null when none was ever confirmed
     * @param status the helper's last status answer; empty when it has not been read
     */
    public static CompiledEvidence of(
            Map<String, String> inputs, String generation, long at, Map<String, String> status) {
        if (inputs == null) return ABSENT;
        return new CompiledEvidence(inputs, generation, at, freshness(generation, status));
    }

    /** The confirmed compile (not the attempt awaiting verification) read from the lens preferences. */
    public static CompiledEvidence from(SharedPreferences prefs, Map<String, String> status) {
        if (!CompositorValues.hasCompiled(prefs)) return ABSENT;
        return of(CompositorValues.compiledValues(prefs), prefs.getString("compiledGeneration", ""),
                prefs.getLong("compiledAt", 0), status);
    }

    private static Freshness freshness(String generation, Map<String, String> status) {
        if (status == null || status.isEmpty()) return Freshness.HISTORICAL;
        String current = status.get("sf_generation");
        boolean live = "1".equals(status.get("helper")) && "1".equals(status.get("sf_loaded"))
                && "1".equals(status.get("sfrefract")) && "1".equals(status.get("sf_rewrite"));
        boolean sameGeneration = generation != null && !generation.isEmpty() && generation.equals(current);
        return live && sameGeneration ? Freshness.CURRENT : Freshness.HISTORICAL;
    }

    public boolean current() {
        return freshness == Freshness.CURRENT;
    }

    public boolean has() {
        return freshness != Freshness.NONE;
    }
}

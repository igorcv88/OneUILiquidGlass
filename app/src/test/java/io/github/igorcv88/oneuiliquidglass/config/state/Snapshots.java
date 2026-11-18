package io.github.igorcv88.oneuiliquidglass.config.state;

import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;

import java.util.HashMap;
import java.util.Map;

/** Builds snapshots for tests: a compile-input map stands for a compile of the loaded compositor. */
public final class Snapshots {
    public static final String GENERATION = "g-now";

    private Snapshots() {}

    /** The helper status of a compositor with the lens loaded, generation {@link #GENERATION}. */
    public static Map<String, String> loadedStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("helper", "1");
        status.put("sf_loaded", "1");
        status.put("sfrefract", "1");
        status.put("sf_rewrite", "1");
        status.put("sf_generation", GENERATION);
        return status;
    }

    /** {@code compiled} null: no compile was ever confirmed; otherwise a current one. */
    public static ConfirmedSnapshot of(Map<String, String> saved, Map<String, String> observed,
            Map<String, String> compiled, boolean enabled, boolean awaiting) {
        Map<String, String> status = loadedStatus();
        return new ConfirmedSnapshot(saved, observed, CompiledEvidence.of(compiled, GENERATION, 1, status),
                status, enabled, true, awaiting, 1);
    }

    public static ConfirmedSnapshot of(ControlState st, Map<String, String> compiled, boolean awaiting) {
        Map<String, String> status = loadedStatus();
        return new ConfirmedSnapshot(st.saved, st.observed, CompiledEvidence.of(compiled, GENERATION, 1, status),
                status, st.enabled, true, awaiting, 1);
    }
}

package io.github.igorcv88.oneuiliquidglass.live;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** The applicability rules have no fixed point for the saved values: the UI disables Apply. */
public final class PlanContradiction extends Exception {
    /** Keys that keep entering and leaving the write set. */
    public final Set<String> keys;

    public PlanContradiction(Set<String> keys) {
        super("Contradictory configuration: " + keys);
        this.keys = Collections.unmodifiableSet(new TreeSet<>(keys));
    }
}

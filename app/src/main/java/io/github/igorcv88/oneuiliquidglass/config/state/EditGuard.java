package io.github.igorcv88.oneuiliquidglass.config.state;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.Controls;

/**
 * The single answer to "may this action touch this control now?" (docs/OULG_UI_PLAN/
 * EFFECTIVE_STATE_CONTRACT.md section 7). It reads only the rules built from confirmed values,
 * so a value being dragged never changes what can be edited, reset, favorited or imported.
 */
public final class EditGuard {
    public enum Action {
        EDIT, RESET_ONE, RESET_GROUP, FAVORITE_ADD, OPEN_EDITOR, BULK_IMPORT, PRESET_APPLY, MATERIAL_SELECT
    }

    public enum Outcome {
        /** Go ahead. */
        ALLOW,
        /** Go ahead; the value is kept and is not in effect now (inactive or off). */
        ALLOW_LATENT,
        /** Open, but as a read-only view with the saved value and the reason. */
        VIEW_ONLY,
        /** Leave this key out of a group operation without failing it. */
        SKIP,
        /** Do not do it; tell the person why. */
        REFUSE
    }

    public static final class Decision {
        public final Outcome outcome;
        /** The rule's one-sentence reason; empty when the key is usable. */
        public final String reason;

        Decision(Outcome outcome, String reason) {
            this.outcome = outcome;
            this.reason = reason;
        }

        /** Whether the action goes ahead in some form. */
        public boolean permitted() {
            return outcome == Outcome.ALLOW || outcome == Outcome.ALLOW_LATENT || outcome == Outcome.VIEW_ONLY;
        }
    }

    private EditGuard() {}

    /**
     * @param surface where the control is shown; null for every surface it applies to
     * @param confirmedRules rules built from confirmed values (ControlState#applicability)
     */
    public static Decision check(
            Action action, String key, Controls.Surface surface, Applicability confirmedRules) {
        if (action == Action.MATERIAL_SELECT) return new Decision(Outcome.ALLOW, "");
        Applicability.Result rule = confirmedRules.on(key, surface);
        boolean usable = rule.usable();
        boolean readOnly = rule.kind == Applicability.Kind.READ_ONLY;
        switch (action) {
            case EDIT:
            case RESET_ONE:
                return usable ? allow() : new Decision(Outcome.REFUSE, rule.reason);
            case RESET_GROUP:
                return usable ? allow() : new Decision(Outcome.SKIP, rule.reason);
            case FAVORITE_ADD:
                if (usable) return allow();
                return readOnly ? new Decision(Outcome.REFUSE, rule.reason) : latent(rule);
            case OPEN_EDITOR:
                return usable ? allow() : new Decision(Outcome.VIEW_ONLY, rule.reason);
            case BULK_IMPORT:
            case PRESET_APPLY:
                if (usable) return allow();
                return readOnly ? new Decision(Outcome.SKIP, rule.reason) : latent(rule);
            default:
                throw new IllegalArgumentException(String.valueOf(action));
        }
    }

    private static Decision allow() {
        return new Decision(Outcome.ALLOW, "");
    }

    private static Decision latent(Applicability.Result rule) {
        return new Decision(Outcome.ALLOW_LATENT, rule.reason);
    }
}

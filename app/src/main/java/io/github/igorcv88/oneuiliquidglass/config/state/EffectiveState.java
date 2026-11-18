package io.github.igorcv88.oneuiliquidglass.config.state;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a control means to the person right now: whether the route uses it, whether it waits for
 * an Apply, whether it is only stored. Built from the confirmed state and the publication plan,
 * never from drafts; it never claims a visual result (docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md
 * section 6).
 */
public final class EffectiveState {
    public enum Kind {
        ACTIVE, PENDING_COMPILE, INACTIVE, OFF, READ_ONLY, EXTERNAL, UNKNOWN
    }

    /** Short qualifiers the UI turns into copy. */
    public enum Note {
        APPLY_TO_TAKE_EFFECT, CHANGED_OUTSIDE_APP, UNKNOWN_COMPILED
    }

    /** Tokens for the value column of an inactive or off row; the UI maps them to string resources. */
    public static final String DISPLAY_INACTIVE = "Inativo", DISPLAY_OFF = "Desligado";

    public static final class Effective {
        public final Kind kind;
        /** What to show in place of a number: the value, or a state token for INACTIVE and OFF. */
        public final String displayValue;
        /** The value kept for another context; null when nothing is saved or the key is usable. */
        public final String latentValue;
        public final String reason;
        public final Set<Note> notes;

        Effective(Kind kind, String displayValue, String latentValue, String reason, Set<Note> notes) {
            this.kind = kind;
            this.displayValue = displayValue;
            this.latentValue = latentValue;
            this.reason = reason;
            this.notes = Collections.unmodifiableSet(notes);
        }
    }

    private EffectiveState() {}

    public static Effective of(ControlState st, PublicationPlan plan, String key, Controls.Surface surface) {
        return of(st, plan, key, surface, null);
    }

    /** {@code draft}: a value being dragged; it changes only the text of an ACTIVE row. */
    public static Effective of(
            ControlState st, PublicationPlan plan, String key, Controls.Surface surface, String draft) {
        Applicability.Result rule = st.applicability().on(key, surface);
        String confirmed = st.confirmed(key);
        Set<Note> none = EnumSet.noneOf(Note.class);
        switch (rule.kind) {
            case READ_ONLY:
                return new Effective(Kind.READ_ONLY, confirmed, null, rule.reason, none);
            case OFF:
                return new Effective(Kind.OFF, DISPLAY_OFF, st.saved.get(key), rule.reason, none);
            case INACTIVE:
                return new Effective(Kind.INACTIVE, DISPLAY_INACTIVE, st.saved.get(key), rule.reason, none);
            default:
                break;
        }
        Set<Note> notes = EnumSet.noneOf(Note.class);
        if (plan.writes.containsKey(key)) {
            notes.add(Note.APPLY_TO_TAKE_EFFECT);
            return new Effective(Kind.PENDING_COMPILE, plan.writes.get(key), null, "", notes);
        }
        if (plan.activeDrift.containsKey(key)) {
            notes.add(Note.APPLY_TO_TAKE_EFFECT);
            if (plan.external.contains(key)) notes.add(Note.CHANGED_OUTSIDE_APP);
            return new Effective(Kind.PENDING_COMPILE, plan.activeDrift.get(key), null, "", notes);
        }
        if (plan.external.contains(key))
            return new Effective(Kind.EXTERNAL, confirmed, null, "", notes);
        if (key.startsWith("sf.") && plan.unknownBaseline) {
            notes.add(Note.UNKNOWN_COMPILED);
            return new Effective(Kind.UNKNOWN, confirmed, null, "", notes);
        }
        return new Effective(Kind.ACTIVE, draft != null ? draft : confirmed, null, "", notes);
    }
}

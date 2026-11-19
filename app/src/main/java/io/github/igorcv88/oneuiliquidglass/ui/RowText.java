package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;

/**
 * What a control row says for an effective state: the value column, a status line, and the
 * accessibility description (title, value, state). State is always text, never only a colour.
 */
final class RowText {
    enum Tone { NORMAL, PENDING, EXTERNAL }

    final String value;
    /** The status line under the title; null when there is nothing to act on. */
    final String line;
    final Tone tone;
    final String description;

    private RowText(String value, String line, Tone tone, String title) {
        this.value = value;
        this.line = line;
        this.tone = tone;
        String state = line == null ? "" : line;
        this.description = title + ", " + value + (state.isEmpty() ? "" : ", " + state);
    }

    /** @param shown the value formatted for the row (a number with its unit, or a choice name) */
    static RowText of(Context c, EffectiveState.Effective e, String title, String shown) {
        switch (e.kind) {
            case INACTIVE:
                return new RowText(c.getString(R.string.state_inactive), ControlReasons.text(c, e.reason), Tone.NORMAL, title);
            case OFF:
                return new RowText(c.getString(R.string.state_off), ControlReasons.text(c, e.reason), Tone.NORMAL, title);
            case PENDING_ACTIVATION:
                return new RowText(shown, c.getString(R.string.state_pending_activation), Tone.PENDING, title);
            case PENDING_COMPILE: {
                String line = c.getString(R.string.state_pending);
                if (e.notes.contains(EffectiveState.Note.CHANGED_OUTSIDE_APP))
                    line += " · " + c.getString(R.string.state_external);
                return new RowText(shown, line, Tone.PENDING, title);
            }
            case EXTERNAL:
                return new RowText(shown, c.getString(R.string.state_external), Tone.EXTERNAL, title);
            case UNKNOWN:
                return new RowText(shown, c.getString(R.string.state_unknown_compiled), Tone.NORMAL, title);
            case READ_ONLY:
                return new RowText(shown, c.getString(R.string.state_read_only), Tone.NORMAL, title);
            default:
                return new RowText(shown, null, Tone.NORMAL, title);
        }
    }

    /** Whether the row keeps its control: INACTIVE and OFF are drawn disabled with the saved value kept. */
    static boolean enabled(EffectiveState.Effective e) {
        return e.kind != EffectiveState.Kind.INACTIVE && e.kind != EffectiveState.Kind.OFF
                && e.kind != EffectiveState.Kind.READ_ONLY;
    }
}

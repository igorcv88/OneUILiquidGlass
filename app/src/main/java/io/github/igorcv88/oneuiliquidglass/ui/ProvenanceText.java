package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.text.format.DateFormat;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.ConfigRepository;

import java.util.Date;

/** Words for the operation that last wrote a value ("edit", "undo", "import"...). */
final class ProvenanceText {
    private ProvenanceText() {}

    static String label(Context context, ConfigRepository.Source source) {
        if (source == null) return context.getString(R.string.undo_previous_change);
        switch (source) {
            case EDIT: return context.getString(R.string.provenance_edit);
            case RESET: return context.getString(R.string.provenance_reset);
            case UNDO: return context.getString(R.string.provenance_undo);
            case IMPORT: return context.getString(R.string.provenance_import);
            case STYLE: return context.getString(R.string.provenance_style);
            case MATERIAL: return context.getString(R.string.provenance_material);
            default: return context.getString(R.string.provenance_adopt);
        }
    }

    /** "Last change: undo, 18/11 14:02"; empty when nothing is recorded. */
    static String line(Context context, ConfigRepository.Provenance provenance) {
        if (provenance == null) return "";
        Date at = new Date(provenance.at);
        String when = DateFormat.format("dd/MM HH:mm", at).toString();
        return context.getString(R.string.provenance_line, label(context, provenance.source), when);
    }
}

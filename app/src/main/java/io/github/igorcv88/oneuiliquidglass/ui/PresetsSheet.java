package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Build;
import android.widget.LinearLayout;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.*;

/**
 * Styles: save the current look, bring it back, return to the defaults, undo. Each of these
 * changes saved values only; compositor values stay pending until applied, and that is said.
 */
final class PresetsSheet extends Sheet {
    private final Host host;

    PresetsSheet(Host host) {
        super(host.activity(), host.theme(), host.activity().getString(R.string.styles));
        this.host = host;
        AppModel m = host.model();
        LinearLayout card = Ui.card(activity, t);
        String saved = m.repo.preferences().getString("preset.custom", null);
        LinearLayout save = Ui.listRow(activity, t, Icon.STAR, activity.getString(R.string.preset_save), saved == null ? activity.getString(R.string.preset_save_desc) : activity.getString(R.string.preset_replace_desc), null);
        save.setOnClickListener(v -> {
            try {
                // A style is a schema 2 file without the system diagnostic: what the person chose, nothing more.
                m.repo.preferences().edit().putString("preset.custom",
                        PresetCodec.encodeV2(m.repo.overrides(), m.state().enabled, device(),
                                versionCode(activity),
                                System.currentTimeMillis(), null, null)).apply();
                host.feedback(activity.getString(R.string.preset_saved));
                dismiss();
            } catch (Exception e) {
                host.feedback(activity.getString(R.string.preset_save_error));
            }
        });
        card.addView(save);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout load = Ui.listRow(activity, t, Icon.BACKUP, activity.getString(R.string.preset_use_saved), saved == null ? activity.getString(R.string.preset_none_saved) : activity.getString(R.string.preset_review_before), null);
        Ui.enable(load, saved != null);
        load.setOnClickListener(v -> {
            dismiss();
            ImportReview.open(host, saved, ConfigRepository.Source.STYLE);
        });
        card.addView(load);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout defaults = Ui.listRow(activity, t, Icon.RESET, activity.getString(R.string.preset_reset), activity.getString(R.string.preset_all_surfaces), null);
        defaults.setOnClickListener(v -> {
            dismiss();
            ImportReview.openDefaults(host);
        });
        card.addView(defaults);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout undo = Ui.listRow(activity, t, Icon.UNDO, activity.getString(R.string.preset_undo), m.repo.canUndo() ? activity.getString(R.string.preset_undo_desc) : activity.getString(R.string.preset_nothing_undo), null);
        Ui.enable(undo, m.repo.canUndo());
        undo.setOnClickListener(v -> {
            // Undo is a review first: the difference is shown before anything is changed.
            dismiss();
            ImportReview.openUndo(host);
        });
        card.addView(undo);
        body().addView(card, Ui.match());
    }

    /** The installed version, written into backups so a file's defaults can be told from this version's. */
    static int versionCode(android.content.Context context) {
        try {
            return (int) context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.DISPLAY;
    }

    /** Diff review before a saved or imported style replaces the current one. */
    static final class PresetReview {
        static void open(Host host, String json) {
            ImportReview.open(host, json, ConfigRepository.Source.IMPORT);
        }

        static String importError(android.content.Context context, Exception error) {
            if (error instanceof PresetCodec.InvalidPreset) {
                PresetCodec.InvalidPreset invalid = (PresetCodec.InvalidPreset) error;
                switch (invalid.error) {
                    case TOO_LARGE: return context.getString(R.string.preset_error_size);
                    case VERSION: return context.getString(R.string.preset_error_version);
                    case TYPE: return context.getString(R.string.preset_error_type, invalid.key);
                    case VALUE: return context.getString(R.string.preset_error_value, invalid.key);
                }
            }
            return context.getString(R.string.preset_invalid);
        }

        static String flagName(android.content.Context context, String key) {
            if (ProductFlags.QUICK_SETTINGS.equals(key)) return context.getString(R.string.home_qs_glass);
            if (ProductFlags.LIVE_ACTIVITY.equals(key)) return context.getString(R.string.home_surface_glass, context.getString(R.string.home_live_activity));
            if (ProductFlags.NOW_BAR.equals(key)) return context.getString(R.string.home_surface_glass, context.getString(R.string.now_bar));
            if (ProductFlags.TOAST.equals(key)) return context.getString(R.string.home_surface_glass, context.getString(R.string.home_toast));
            String[] p = key.split("\\.");
            Controls.Surface s = Controls.Surface.of(p.length > 1 ? p[1] : "");
            if (p.length == 3) return context.getString(R.string.preset_glass_surface, HomePage.surfaceLabel(context, s));
            String layer = p.length > 3 ? UiLabels.layer(context, p[3]) : key;
            return context.getString(R.string.preset_layer, HomePage.surfaceLabel(context, s), layer);
        }
    }
}

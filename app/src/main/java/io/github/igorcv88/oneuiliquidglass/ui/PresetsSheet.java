package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Build;
import android.widget.LinearLayout;

import io.github.igorcv88.oneuiliquidglass.config.*;
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
                m.repo.preferences().edit().putString("preset.custom",
                        PresetCodec.encode(m.repo.overrides(), m.state().enabled, device())).apply();
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
            PresetReview.open(host, saved);
        });
        card.addView(load);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout defaults = Ui.listRow(activity, t, Icon.RESET, activity.getString(R.string.preset_reset), activity.getString(R.string.preset_all_surfaces), null);
        defaults.setOnClickListener(v -> confirmDefaults());
        card.addView(defaults);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout undo = Ui.listRow(activity, t, Icon.UNDO, activity.getString(R.string.preset_undo), m.repo.canUndo() ? activity.getString(R.string.preset_undo_desc) : activity.getString(R.string.preset_nothing_undo), null);
        Ui.enable(undo, m.repo.canUndo());
        undo.setOnClickListener(v -> {
            host.feedback(m.undo() ? activity.getString(R.string.preset_undone) : activity.getString(R.string.preset_nothing_undo));
            dismiss();
        });
        card.addView(undo);
        body().addView(card, Ui.match());
    }

    private void confirmDefaults() {
        dismiss();
        Sheet s = new Sheet(activity, t, activity.getString(R.string.preset_reset_question));
        s.text(activity.getString(R.string.preset_reset_warning));
        s.action(host.activity().getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action(activity.getString(R.string.preset_reset), Ui.ButtonStyle.FILLED, () -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (SettingSpec spec : SettingsRegistry.all())
                if (Controls.editable(spec.key)) m.put(spec.key, spec.defaultValue);
            host.model().repo.replace(m, true);
            host.model().notifyChanged();
            host.feedback(activity.getString(R.string.preset_restored));
            s.dismiss();
        });
        s.show();
    }

    static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.DISPLAY;
    }

    /** Diff review before a saved or imported style replaces the current one. */
    static final class PresetReview {
        static void open(Host host, String json) {
            Theme t = host.theme();
            PresetCodec.Parsed parsed;
            try {
                parsed = PresetCodec.parse(json);
            } catch (Exception e) {
                host.feedback(host.activity().getString(R.string.preset_invalid));
                return;
            }
            AppModel m = host.model();
            ControlState st = m.state();
            Sheet s = new Sheet(host.activity(), t, host.activity().getString(R.string.preset_use_question));
            List<String> lines = new ArrayList<>();
            int compositor = 0;
            for (Map.Entry<String, String> e : parsed.values.entrySet()) {
                Controls.Control c = Controls.get(e.getKey());
                String now = c != null ? st.shown(e.getKey()) : st.shown(e.getKey());
                if (now != null && now.equals(e.getValue())) continue;
                if (c != null) {
                    lines.add(ControlTitles.title(host.activity(), c) + " · " + surfaces(host.activity(), c) + ": " + ControlValues.display(host.activity(), c, now) + " → " + ControlValues.display(host.activity(), c, e.getValue()));
                    if (c.needsRestart()) compositor++;
                } else lines.add(flagName(host.activity(), e.getKey()) + ": " + host.activity().getString("1".equals(e.getValue()) ? R.string.switch_on : R.string.switch_off));
            }
            StringBuilder b = new StringBuilder();
            if (lines.isEmpty()) b.append(host.activity().getString(R.string.preset_no_changes));
            else b.append(host.activity().getString(lines.size() == 1 ? R.string.preset_change_one : R.string.preset_change_many, lines.size()) + "\n");
            for (int i = 0; i < Math.min(40, lines.size()); i++) b.append("• ").append(lines.get(i)).append('\n');
            if (lines.size() > 40) b.append(host.activity().getString(R.string.preset_and_more, lines.size() - 40)).append('\n');
            if (parsed.enabled != st.enabled) b.append("\n").append(host.activity().getString(parsed.enabled ? R.string.preset_glass_enabled : R.string.preset_glass_disabled));
            if (compositor > 0) b.append("\n").append(host.activity().getString(R.string.preset_lens_pending));
            if (!parsed.unknown.isEmpty()) b.append("\n").append(host.activity().getString(R.string.preset_ignored, parsed.unknown.size()));
            s.text(b.toString().trim());
            s.action(host.activity().getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
            s.action(host.activity().getString(R.string.preset_use), Ui.ButtonStyle.FILLED, () -> {
                m.repo.replace(parsed.values, true, parsed.enabled);
                m.notifyChanged();
                host.feedback(host.activity().getString(R.string.preset_applied));
                s.dismiss();
            });
            s.show();
        }

        private static String surfaces(android.content.Context context, Controls.Control c) {
            if (c.surfaces.size() == 3) return context.getString(R.string.preset_all);
            StringBuilder b = new StringBuilder();
            for (Controls.Surface s : c.surfaces) b.append(b.length() == 0 ? "" : "/").append(HomePage.surfaceLabel(context, s));
            return b.toString();
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

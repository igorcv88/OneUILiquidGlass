package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.ConfigRepository;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.ImportPlan;
import io.github.igorcv88.oneuiliquidglass.config.PresetCodec;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Segmented;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Toggle;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The review before a backup, a saved style or Undo changes what is saved. It renders one
 * {@link ImportPlan}; the confirmation applies that same plan in one transaction, so what the
 * person read is what is saved (docs/OULG_UI_PLAN/ACTIVATION_AND_BACKUP_CONTRACT.md section 6).
 * Nothing is published and nothing restarts.
 */
final class ImportReview {
    /** Lines shown per group; the rest is counted. */
    private static final int MAX_LINES = 40;

    private final Host host;
    private final Activity a;
    private final Theme t;
    private final AppModel m;
    private final PresetCodec.Parsed parsed;
    private final ConfigRepository.Source source;
    private final boolean undo;
    private final Sheet sheet;
    private final LinearLayout summary;
    private ImportPlan.Mode mode = ImportPlan.Mode.REPLACE;
    private boolean useBackupDefaults, adoptTerminal;
    private ImportPlan plan;

    private ImportReview(Host host, String title, PresetCodec.Parsed parsed, ConfigRepository.Source source,
            boolean undo, boolean chooseMode) {
        this.host = host;
        this.a = host.activity();
        this.t = host.theme();
        this.m = host.model();
        this.parsed = parsed;
        this.source = source;
        this.undo = undo;
        sheet = new Sheet(a, t, title);
        summary = Ui.column(a);
        if (chooseMode) modeChoice();
        optionToggles();
        sheet.body().addView(summary, Ui.match());
        sheet.action(a.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, sheet::dismiss);
        sheet.action(a.getString(R.string.preset_use), Ui.ButtonStyle.FILLED, this::confirm);
        render();
        sheet.show();
    }

    /** A saved file or style: Replace or Merge, optional defaults and Terminal values. */
    static void open(Host host, String json, ConfigRepository.Source source) {
        PresetCodec.Parsed parsed;
        try {
            parsed = PresetCodec.parse(json);
        } catch (Exception e) {
            host.feedback(PresetsSheet.PresetReview.importError(host.activity(), e));
            return;
        }
        new ImportReview(host, host.activity().getString(R.string.preset_use_question), parsed, source, false,
                source == ConfigRepository.Source.IMPORT);
    }

    /** Undo as a review: the snapshot replaces what is saved, after the person has seen the difference. */
    static void openUndo(Host host) {
        ConfigRepository.UndoSnapshot snapshot = host.model().repo.undoSnapshot();
        if (snapshot == null) {
            host.feedback(host.activity().getString(R.string.preset_nothing_undo));
            return;
        }
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        parsed.values.putAll(snapshot.overrides);
        parsed.enabled = snapshot.enabled;
        new ImportReview(host, host.activity().getString(R.string.undo_review_title,
                ProvenanceText.label(host.activity(), snapshot.label)), parsed, ConfigRepository.Source.UNDO, true, false);
    }

    /**
     * Restore defaults as a review: every editable value goes back to its code default and stays
     * explicit (republishing a compositor value that was published needs it), flags go back to theirs.
     */
    static void openDefaults(Host host) {
        PresetCodec.Parsed parsed = new PresetCodec.Parsed();
        for (SettingSpec spec : SettingsRegistry.all())
            if (Controls.editable(spec.key) && !spec.helperOwned() && spec.type != SettingSpec.Type.READONLY)
                parsed.values.put(spec.key, spec.defaultValue);
        parsed.enabled = host.model().state().enabled;
        new ImportReview(host, host.activity().getString(R.string.preset_reset_question), parsed,
                ConfigRepository.Source.RESET, false, false);
    }

    private void modeChoice() {
        Segmented choice = new Segmented(a, t, new String[] {a.getString(R.string.import_mode_replace),
                a.getString(R.string.import_mode_merge)}, 0);
        choice.listener(index -> {
            mode = index == 0 ? ImportPlan.Mode.REPLACE : ImportPlan.Mode.MERGE;
            render();
        });
        sheet.body().addView(choice, Ui.match(0, t.dp(8)));
    }

    private void optionToggles() {
        ImportPlan probe = m.importPlan(parsed, ImportPlan.Mode.REPLACE, false, false);
        if (probe.hasBackupDefaults) toggle(R.string.import_use_backup_defaults, on -> useBackupDefaults = on);
        if (probe.hasTerminal) toggle(R.string.import_adopt_terminal, on -> adoptTerminal = on);
    }

    private void toggle(int label, java.util.function.Consumer<Boolean> change) {
        Toggle toggle = new Toggle(a, t);
        LinearLayout row = Ui.switchRow(a, t, null, a.getString(label), null, toggle);
        toggle.listener((x, on) -> {
            change.accept(on);
            render();
        });
        LinearLayout card = Ui.card(a, t);
        card.addView(row);
        sheet.body().addView(card, Ui.match(t.dp(4), 0));
    }

    private void render() {
        plan = m.importPlan(parsed, mode, useBackupDefaults, adoptTerminal);
        summary.removeAllViews();
        StringBuilder b = new StringBuilder();
        if (plan.noChanges()) b.append(a.getString(R.string.preset_no_changes)).append('\n');
        group(b, R.string.import_group_changed, changedLines(plan.changed));
        group(b, R.string.import_group_added, changedLines(plan.added));
        group(b, R.string.import_group_removed, removedLines());
        group(b, R.string.import_group_dormant, keyLines(plan.dormant));
        group(b, R.string.import_group_ignored, new ArrayList<>(plan.ignored));
        if (plan.enabledChanges)
            b.append('\n').append(a.getString(plan.enabled ? R.string.preset_glass_enabled : R.string.preset_glass_disabled)).append('\n');
        if (plan.needsLens) b.append('\n').append(a.getString(R.string.import_needs_lens)).append('\n');
        if (plan.diagnosticPresent && !undo) b.append('\n').append(a.getString(R.string.import_diagnostic_note)).append('\n');
        summary.addView(Ui.text(a, t, b.toString().trim(), Theme.LABEL, t.ink, false));
    }

    private void group(StringBuilder b, int titleRes, List<String> lines) {
        if (lines.isEmpty()) return;
        b.append(b.length() == 0 ? "" : "\n").append(a.getString(titleRes, lines.size())).append('\n');
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) b.append("• ").append(lines.get(i)).append('\n');
        if (lines.size() > MAX_LINES) b.append(a.getString(R.string.preset_and_more, lines.size() - MAX_LINES)).append('\n');
    }

    private List<String> changedLines(List<ImportPlan.Change> changes) {
        List<String> out = new ArrayList<>();
        for (ImportPlan.Change c : changes)
            out.add(name(c.key) + ": " + (c.from == null ? "" : value(c.key, c.from) + " → ") + value(c.key, c.to));
        return out;
    }

    private List<String> removedLines() {
        List<String> out = new ArrayList<>();
        for (ImportPlan.Change c : plan.removed)
            out.add(name(c.key) + ": " + value(c.key, c.from) + " → " + value(c.key, c.to));
        return out;
    }

    private List<String> keyLines(List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) out.add(name(key) + ": " + value(key, plan.result.get(key)));
        return out;
    }

    private String name(String key) {
        Controls.Control c = Controls.get(key);
        if (c == null) return PresetsSheet.PresetReview.flagName(a, key);
        return ControlTitles.title(a, c) + " · " + SearchSheet.surfaces(a, c);
    }

    private String value(String key, String raw) {
        Controls.Control c = Controls.get(key);
        if (c == null) return a.getString("1".equals(raw) ? R.string.switch_on : R.string.switch_off);
        SettingSpec spec = SettingsRegistry.get(key);
        return ControlValues.display(a, c, raw == null && spec != null ? spec.defaultValue : raw);
    }

    private void confirm() {
        m.applyPlan(plan, source);
        host.feedback(a.getString(R.string.preset_applied));
        sheet.dismiss();
    }
}

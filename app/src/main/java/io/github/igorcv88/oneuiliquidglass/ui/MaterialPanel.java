package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.MaterialStatus;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.Map;

/**
 * The global glass material choice (Frosted / Clear) with an honest account of where it stands:
 * saved, waiting for a recompilation, not confirmed, unavailable or applied. Choosing only saves
 * the two compositor selectors; root, properties and the restart belong to the lens screen, which
 * lists every waiting value and applies them together after one confirmation.
 */
final class MaterialPanel {
    private static final int REVIEW_LINES = 40;

    private final Host host;
    private final Theme t;
    private final LinearLayout root, statusSlot, actions;
    private final Segmented choices;
    private final GlassMaterial[] materials = GlassMaterial.values();
    private String builtKey = "";

    MaterialPanel(Host host) {
        this.host = host;
        t = host.theme();
        Activity a = host.activity();
        root = Ui.card(a, t);
        root.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(14));
        TextView title = Ui.text(a, t, a.getString(R.string.material_title), Theme.HEADLINE, t.ink, true);
        title.setAccessibilityHeading(true);
        root.addView(title);
        TextView subtitle = Ui.text(a, t, a.getString(R.string.material_subtitle), Theme.LABEL, t.inkMuted, false);
        subtitle.setPadding(0, t.dp(2), 0, t.dp(12));
        root.addView(subtitle);
        String[] labels = new String[materials.length];
        for (int i = 0; i < materials.length; i++) labels[i] = UiLabels.material(a, materials[i]);
        choices = new Segmented(a, t, labels, 0);
        choices.setContentDescription(a.getString(R.string.material_title));
        choices.listener(index -> choose(materials[index]));
        root.addView(choices, Ui.match());
        TextView scope = Ui.text(a, t, a.getString(R.string.material_scope), Theme.CAPTION, t.inkFaint, false);
        scope.setPadding(t.dp(4), t.dp(8), t.dp(4), 0);
        root.addView(scope);
        statusSlot = Ui.column(a);
        root.addView(statusSlot, Ui.match(t.dp(12), 0));
        actions = Ui.column(a);
        root.addView(actions, Ui.match());
    }

    View view() {
        return root;
    }

    private void choose(GlassMaterial material) {
        AppModel m = host.model();
        AppModel.Result r = m.selectMaterial(material);
        Activity a = host.activity();
        if (!r.ok) {
            host.feedback(r.conflict ? a.getString(R.string.error_conflict) : r.message);
            if (r.conflict) host.openConflicts();
            bind();
            return;
        }
        MaterialStatus status = m.materialStatus();
        host.feedback(a.getString(status == MaterialStatus.PENDING ? R.string.material_saved_pending
                : status == MaterialStatus.UNAVAILABLE ? R.string.material_saved_unavailable
                : R.string.material_saved));
    }

    void bind() {
        AppModel m = host.model();
        GlassMaterial material = m.material();
        MaterialStatus status = m.materialStatus();
        choices.select(material == null ? -1 : material.ordinal(), false);
        Map<String, String> recommended = material == GlassMaterial.CLEAR ? m.recommendedClearOffer() : java.util.Collections.emptyMap();
        Map<String, String> onDevice = material == GlassMaterial.CLEAR && recommended.isEmpty()
                ? m.clearValuesOnDevice() : java.util.Collections.emptyMap();
        boolean revert = m.canRevertMaterial();
        ControlState st = m.state();
        String key = status + ":" + material + ":" + revert + ":" + recommended.size() + ":" + onDevice.size()
                + ":" + st.shown(GlassMaterial.HU_SELECTOR) + ":" + st.shown(GlassMaterial.KG_SELECTOR);
        if (key.equals(builtKey)) return;
        builtKey = key;
        Activity a = host.activity();
        statusSlot.removeAllViews();
        actions.removeAllViews();
        LinearLayout notice = status(a, status, st);
        if (notice != null) statusSlot.addView(notice, Ui.match());
        if (revert) actions.addView(button(a.getString(R.string.material_undo), Ui.ButtonStyle.TEXT, () -> {
            AppModel.Result r = host.model().revertMaterial();
            host.feedback(r.ok ? host.activity().getString(R.string.material_reverted) : r.message);
        }), Ui.match(t.dp(4), 0));
        if (!recommended.isEmpty())
            actions.addView(button(a.getString(R.string.material_recommended), Ui.ButtonStyle.TONAL,
                    () -> review(recommended, R.string.material_recommended, R.string.material_recommended_intro,
                            R.string.material_recommended_use)), Ui.match(t.dp(8), 0));
        if (!onDevice.isEmpty())
            actions.addView(button(a.getString(R.string.material_keep_device), Ui.ButtonStyle.TONAL,
                    () -> review(onDevice, R.string.material_keep_device, R.string.material_keep_device_intro,
                            R.string.material_keep_device_use)), Ui.match(t.dp(8), 0));
    }

    private TextView button(String label, Ui.ButtonStyle style, Runnable action) {
        TextView b = Ui.button(host.activity(), t, label, null, style, true);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout status(Activity a, MaterialStatus status, ControlState st) {
        switch (status) {
            case APPLIED:
                return Ui.notice(a, t, Ui.Tone.OK, a.getString(R.string.material_status_applied), null, null);
            case PENDING:
                return Ui.notice(a, t, Ui.Tone.INFO, a.getString(R.string.material_status_pending),
                        a.getString(R.string.material_review_apply), host::openLens);
            case UNCONFIRMED:
                return Ui.notice(a, t, Ui.Tone.WARN, a.getString(R.string.material_status_unconfirmed),
                        a.getString(R.string.home_check), host::openLens);
            case UNAVAILABLE:
                return Ui.notice(a, t, Ui.Tone.WARN, a.getString(R.string.material_status_unavailable),
                        a.getString(R.string.material_set_up), host::openLens);
            case MIXED:
                return Ui.notice(a, t, Ui.Tone.INFO, a.getString(R.string.material_status_mixed,
                        UiLabels.selector(a, st.shown(GlassMaterial.HU_SELECTOR)),
                        UiLabels.selector(a, st.shown(GlassMaterial.KG_SELECTOR))), null, null);
            default:
                return Ui.notice(a, t, Ui.Tone.INFO, a.getString(R.string.material_status_not_required), null, null);
        }
    }

    /** Lists what a set of Clear values changes and saves it only on an explicit confirmation. */
    private void review(Map<String, String> values, int title, int intro, int confirm) {
        Activity a = host.activity();
        ControlState st = host.model().state();
        StringBuilder b = new StringBuilder(a.getString(intro)).append("\n");
        int shown = 0;
        for (Map.Entry<String, String> e : values.entrySet()) {
            Controls.Control c = Controls.get(e.getKey());
            if (c == null) continue;
            if (shown++ >= REVIEW_LINES) break;
            b.append("\n• ").append(ControlTitles.title(a, c)).append(" · ").append(SearchSheet.surfaces(a, c))
                    .append(": ").append(ControlValues.display(a, c, st.shown(e.getKey()))).append(" → ")
                    .append(ControlValues.display(a, c, e.getValue()));
        }
        Sheet s = new Sheet(a, t, a.getString(title));
        s.text(b.toString());
        s.action(a.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action(a.getString(confirm), Ui.ButtonStyle.FILLED, () -> {
            AppModel.Result r = host.model().saveClearValues(values);
            s.dismiss();
            if (r.ok) host.feedback(a.getString(R.string.material_values_saved));
            else {
                host.feedback(r.conflict ? a.getString(R.string.error_conflict) : r.message);
                if (r.conflict) host.openConflicts();
            }
        });
        s.show();
    }
}

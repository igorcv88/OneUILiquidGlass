package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link PublicationPlan} as the groups the person confirms (docs/OULG_UI_PLAN/
 * ACTIVATION_AND_BACKUP_CONTRACT.md section 4): what is restored from the saved settings, what
 * they changed, what changed outside the app, what is saved without effect, and earlier Terminal
 * values that no longer exist (adopted only by an explicit action). The checkpoint is never shown
 * as the current value.
 */
final class LensPlanView {
    private final Host host;
    private final AppModel m;
    private final Theme t;
    private final Activity a;

    LensPlanView(Host host) {
        this.host = host;
        this.m = host.model();
        this.t = host.theme();
        this.a = host.activity();
    }

    /** One line of a group, with an optional adoption action for its key. */
    private static final class Line {
        final String text;
        final String adoptKey, adoptValue;

        Line(String text, String adoptKey, String adoptValue) {
            this.text = text;
            this.adoptKey = adoptKey;
            this.adoptValue = adoptValue;
        }
    }

    void render(LinearLayout box, PublicationPlan plan) {
        box.removeAllViews();
        Map<String, String> observed = m.state().observed;
        Map<String, String> compiled = m.compiledEvidence().inputs;
        List<Line> restore = new ArrayList<>(), changes = new ArrayList<>(), external = new ArrayList<>(),
                dormant = new ArrayList<>(), lost = new ArrayList<>();
        for (Map.Entry<String, PublicationPlan.Write> e : plan.writes.entrySet()) {
            Controls.Control c = Controls.get(e.getKey());
            if (c == null) continue;
            String value = display(c, e.getValue().value);
            if (e.getValue().reason == PublicationPlan.WriteReason.RESTORE)
                restore.add(new Line(label(c) + ": " + value + " · " + a.getString(R.string.activation_restore_origin), null, null));
            else
                changes.add(new Line(label(c) + ": " + display(c, observed.get(e.getKey())) + " → " + value, null, null));
        }
        for (Map.Entry<String, PublicationPlan.Drift> e : plan.drift.entrySet()) {
            Controls.Control c = Controls.get(e.getKey());
            if (c == null) continue;
            external.add(new Line(label(c) + ": " + display(c, compiled.get(e.getKey())) + " → " + display(c, e.getValue().value), null, null));
        }
        for (String key : plan.dormant) {
            Controls.Control c = Controls.get(key);
            if (c != null) dormant.add(new Line(label(c) + ": " + display(c, m.state().saved.get(key)), null, null));
        }
        for (String key : plan.lostExternal) {
            Controls.Control c = Controls.get(key);
            if (c != null) lost.add(new Line(label(c) + ": " + display(c, compiled.get(key)), key, compiled.get(key)));
        }
        group(box, R.string.activation_restore_group, restore, true);
        group(box, R.string.activation_changes_group, changes, false);
        group(box, R.string.activation_external_group, external, false);
        group(box, R.string.activation_dormant_group, dormant, true);
        group(box, R.string.activation_lost_external_group, lost, false);
    }

    private String label(Controls.Control c) {
        return ControlTitles.title(a, c) + " · " + SearchSheet.surfaces(a, c);
    }

    private String display(Controls.Control c, String raw) {
        if (raw == null || raw.isEmpty()) raw = c.spec().defaultValue;
        return ControlValues.display(a, c, raw);
    }

    private void group(LinearLayout box, int titleRes, List<Line> lines, boolean collapsed) {
        if (lines.isEmpty()) return;
        String title = a.getString(titleRes, lines.size());
        LinearLayout card = Ui.card(a, t);
        LinearLayout body = Ui.column(a);
        if (collapsed) {
            View[] d = Ui.disclosure(a, t, null, title, null, false, null);
            card.addView(d[0]);
            body = (LinearLayout) d[1];
            card.addView(body);
        } else {
            box.addView(Ui.section(a, t, title));
            card.addView(body);
        }
        for (Line line : lines) body.addView(row(line));
        box.addView(card, Ui.match());
    }

    private View row(Line line) {
        LinearLayout row = Ui.column(a);
        row.setPadding(t.dp(18), t.dp(8), t.dp(18), t.dp(8));
        row.addView(Ui.text(a, t, line.text, Theme.LABEL, t.ink, false));
        if (line.adoptKey != null) {
            TextView adopt = Ui.button(a, t, a.getString(R.string.activation_adopt), null, Ui.ButtonStyle.OUTLINED, true);
            adopt.setOnClickListener(v -> reviewAdoption(line));
            row.addView(adopt);
        }
        return row;
    }

    /** Adopting is a decision: a short review first, then the value is saved as the person own, with its provenance. */
    private void reviewAdoption(Line line) {
        Sheet s = new Sheet(a, t, a.getString(R.string.activation_adopt));
        s.text(line.text);
        s.action(a.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action(a.getString(R.string.activation_adopt), Ui.ButtonStyle.FILLED, () -> {
            AppModel.Result r = m.adoptExternal(line.adoptKey, line.adoptValue);
            s.dismiss();
            if (!r.ok) host.feedback(r.message);
        });
        s.show();
    }

    /** Keys of the plan, for callers that only need to know whether there is anything to show. */
    static boolean hasContent(PublicationPlan plan) {
        Set<String> any = new java.util.HashSet<>(plan.writes.keySet());
        any.addAll(plan.drift.keySet());
        any.addAll(plan.dormant);
        any.addAll(plan.lostExternal);
        return !any.isEmpty();
    }
}

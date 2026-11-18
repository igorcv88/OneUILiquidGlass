package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Icon;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Technical and diagnostic controls (docs/OULG_UI_PLAN/SCREEN_SPECS.md section 6): technical
 * groups, the system state that cannot be edited, legacy keys with no effect, the material
 * selector with its "per card" test option, the full parameter list and the report. Nothing here
 * is a favorite or appears on the surface pages.
 */
final class TechnicalSheet extends Sheet {
    private final Host host;
    private final Theme t;
    private final Supplier<String> report;
    private final List<ControlRow> rows = new ArrayList<>();
    private final AppModel.Listener listener = this::bindRows;

    TechnicalSheet(Host host, Supplier<String> report) {
        super(host.activity(), host.theme(), host.activity().getString(R.string.settings_technical));
        this.host = host;
        this.t = host.theme();
        this.report = report;
        Activity a = host.activity();
        LinearLayout body = body();
        body.addView(Ui.notice(a, t, Ui.Tone.WARN, a.getString(R.string.technical_warning), null, null), Ui.match());
        for (ProductLayout.Group g : ProductLayout.technical()) section(body, a.getString(g.titleRes), g.keys);
        materialSelector(body, a);
        section(body, a.getString(R.string.technical_group_read_only), ProductLayout.readOnly());
        section(body, a.getString(R.string.technical_group_legacy), ProductLayout.legacy());
        tools(body, a);
        AppModel model = host.model();
        model.listen(listener);
        onClosed(() -> model.unlisten(listener));
        bindRows();
    }

    /** The first surface a control is on: where the editor and its rules are evaluated. */
    static Controls.Surface surfaceOf(Controls.Control c) {
        return c.surfaces.iterator().next();
    }

    private void section(LinearLayout body, String title, List<String> keys) {
        List<Controls.Control> controls = new ArrayList<>();
        for (String key : keys) {
            Controls.Control c = Controls.get(key);
            if (c != null && (Controls.editable(key) || ProductLayout.readOnly().contains(key))) controls.add(c);
        }
        if (controls.isEmpty()) return;
        body.addView(Ui.section(host.activity(), t, title));
        LinearLayout card = Ui.card(host.activity(), t);
        boolean first = true;
        for (Controls.Control c : controls) {
            if (!first) card.addView(Ui.divider(host.activity(), t, t.dp(20)));
            first = false;
            ControlRow row = new ControlRow(host.activity(), host, c, surfaceOf(c));
            card.addView(row, Ui.match());
            rows.add(row);
        }
        body.addView(card, Ui.match());
    }

    /** The two compositor selectors; "per card" (2) can only be chosen here. */
    private void materialSelector(LinearLayout body, Activity a) {
        body.addView(Ui.section(a, t, a.getString(R.string.material_title)));
        LinearLayout card = Ui.card(a, t);
        LinearLayout perCard = Ui.listRow(a, t, Icon.LAYERS, a.getString(R.string.material_per_card),
                a.getString(R.string.material_scope), null);
        perCard.setOnClickListener(v -> {
            AppModel.Result r = host.model().selectPerCard();
            host.feedback(r.ok ? a.getString(R.string.material_saved_pending) : r.message);
        });
        card.addView(perCard);
        body.addView(card, Ui.match());
    }

    private void tools(LinearLayout body, Activity a) {
        body.addView(Ui.section(a, t, a.getString(R.string.technical_group_diagnostic)));
        LinearLayout card = Ui.card(a, t);
        LinearLayout all = Ui.listRow(a, t, Icon.CODE, a.getString(R.string.all_parameters),
                a.getString(R.string.all_parameters_desc), Ui.chevron(a, t));
        all.setOnClickListener(v -> new SearchSheet(host, Controls.Surface.HEADSUP, true).show());
        card.addView(all);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout copy = Ui.listRow(a, t, Icon.COPY, a.getString(R.string.copy_report),
                a.getString(R.string.copy_report_desc), null);
        copy.setOnClickListener(v -> host.copy(report.get()));
        card.addView(copy);
        body.addView(card, Ui.match());
    }

    private void bindRows() {
        AppModel m = host.model();
        ControlState st = m.state();
        Applicability app = m.rules();
        EffectiveView view = m.effectiveView();
        for (ControlRow r : rows) {
            EffectiveState.Effective e = view.of(r.control.key, surfaceOf(r.control));
            r.bind(st, app, e);
        }
    }
}

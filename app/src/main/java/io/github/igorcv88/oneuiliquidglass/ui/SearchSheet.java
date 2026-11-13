package io.github.igorcv88.oneuiliquidglass.ui;

import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.text.Normalizer;
import java.util.*;

/**
 * Finds any control by everyday name, hint, help or technical name, across surfaces. With
 * {@code technical} it lists every parameter grouped by section, the complete map of the app.
 */
final class SearchSheet extends Sheet {
    private final Host host;
    private final Controls.Surface surface;
    private final boolean technical;
    private final LinearLayout results;
    private final EditText query;

    SearchSheet(Host host, Controls.Surface surface) {
        this(host, surface, false);
    }

    SearchSheet(Host host, Controls.Surface surface, boolean technical) {
        super(host.activity(), host.theme(), host.activity().getString(technical ? R.string.all_parameters : R.string.search_title));
        this.host = host;
        this.surface = surface;
        this.technical = technical;
        if (technical) subtitle(activity.getString(R.string.search_technical_desc));
        query = new EditText(activity);
        query.setHint(activity.getString(technical ? R.string.search_filter : R.string.search_hint));
        query.setSingleLine(true);
        query.setTextColor(t.ink);
        query.setHintTextColor(t.inkFaint);
        query.setTextSize(Theme.BODY);
        query.setBackground(Ui.rounded(t.surface, t.dpf(24)));
        query.setPadding(t.dp(18), t.dp(12), t.dp(18), t.dp(12));
        query.setMinHeight(t.dp(48));
        android.graphics.drawable.Drawable icon = Icon.SEARCH.drawable(t.inkFaint, t.dpf(20));
        icon.setBounds(0, 0, t.dp(20), t.dp(20));
        query.setCompoundDrawablesRelative(icon, null, null, null);
        query.setCompoundDrawablePadding(t.dp(10));
        body().addView(query, Ui.match(0, t.dp(8)));
        results = Ui.column(activity);
        body().addView(results, Ui.match());
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { render(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        render();
        if (!technical) query.post(() -> {
            query.requestFocus();
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    static boolean matchesControl(android.content.Context context, Controls.Control c, String query) {
        String hay = fold(ControlTitles.title(context, c) + " " + c.title + " " + ControlDescriptions.hint(context, c) + " " + ControlDescriptions.help(context, c) + " " + c.hint + " " + c.help + " " + c.spec().fullKey()
                + " " + c.section.label + " " + UiLabels.section(context, c.section));
        return hay.contains(fold(query == null ? "" : query.trim()));
    }

    private void render() {
        results.removeAllViews();
        String q = fold(query.getText().toString().trim());
        if (!technical && q.isEmpty()) {
            results.addView(hint(activity.getString(R.string.search_prompt)));
            return;
        }
        ControlState st = host.model().state();
        Applicability app = st.applicability();
        Map<Controls.Section, List<Controls.Control>> groups = new EnumMap<>(Controls.Section.class);
        for (Controls.Control c : Controls.all()) {
            if (!technical && c.tier == Controls.Tier.INTERNAL) continue;
            // Ordinary search follows the material that decides the surface; the technical list
            // keeps every parameter and says which material uses it.
            if (!technical && !GlassMaterial.visible(c.key, c.on(surface) ? surface : c.surfaces.iterator().next(), st::shown)) continue;
            if (!matchesControl(activity, c, q)) continue;
            groups.computeIfAbsent(c.section, k -> new ArrayList<>()).add(c);
        }
        if (groups.isEmpty()) {
            results.addView(hint(activity.getString(R.string.search_no_results)));
            return;
        }
        for (Map.Entry<Controls.Section, List<Controls.Control>> g : groups.entrySet()) {
            results.addView(Ui.section(activity, t, UiLabels.section(activity, g.getKey())));
            LinearLayout card = Ui.card(activity, t);
            for (Controls.Control c : g.getValue()) {
                if (card.getChildCount() > 0) card.addView(Ui.divider(activity, t, t.dp(20)));
                Controls.Surface where = c.on(surface) ? surface : c.surfaces.iterator().next();
                Applicability.Result a = app.on(c.key, where);
                String value = ControlValues.display(host.activity(), c, st.shown(c.key));
                String sub = surfaces(activity, c) + " · " + value + (a.usable() || a.kind == Applicability.Kind.READ_ONLY ? "" : " · " + ControlReasons.text(activity, a.reason));
                if (technical) sub += "\n" + c.spec().fullKey() + materialNote(c.key);
                LinearLayout r = Ui.listRow(activity, t, null, ControlTitles.title(activity, c), sub, Controls.editable(c.key) ? Ui.chevron(activity, t) : null);
                if (Controls.editable(c.key)) r.setOnClickListener(v -> {
                    dismiss();
                    host.openControl(c.key, where);
                });
                else r.setClickable(false);
                card.addView(r);
            }
            results.addView(card, Ui.match());
        }
    }

    private String materialNote(String key) {
        switch (GlassMaterial.scope(key)) {
            case CLEAR_ONLY: return " · " + activity.getString(R.string.search_material_clear);
            case FROSTED_ONLY: return " · " + activity.getString(R.string.search_material_frosted);
            default: return "";
        }
    }

    private TextView hint(String s) {
        TextView v = Ui.text(activity, t, s, Theme.LABEL, t.inkMuted, false);
        v.setPadding(t.dp(8), t.dp(12), t.dp(8), t.dp(12));
        return v;
    }

    static String surfaces(android.content.Context context, Controls.Control c) {
        if (c.surfaces.size() == 3) return context.getString(R.string.search_all_surfaces);
        String out = "";
        for (Controls.Surface s : c.surfaces) {
            String name = HomePage.surfaceLabel(context, s);
            out = out.isEmpty() ? name : context.getString(R.string.search_surfaces_join, out, name);
        }
        return out;
    }
}

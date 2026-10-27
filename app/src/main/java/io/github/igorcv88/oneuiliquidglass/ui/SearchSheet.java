package io.github.igorcv88.oneuiliquidglass.ui;

import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
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
        super(host.activity(), host.theme(), technical ? "Todos os parâmetros" : "Buscar ajuste");
        this.host = host;
        this.surface = surface;
        this.technical = technical;
        if (technical) subtitle("Lista completa, incluindo ajustes técnicos e de diagnóstico.");
        query = new EditText(activity);
        query.setHint(technical ? "Filtrar por nome ou propriedade" : "Nome, efeito ou propriedade");
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

    private void render() {
        results.removeAllViews();
        String q = fold(query.getText().toString().trim());
        if (!technical && q.isEmpty()) {
            results.addView(hint("Digite para buscar entre todos os ajustes dos três ambientes."));
            return;
        }
        ControlState st = host.model().state();
        Applicability app = st.applicability();
        Map<Controls.Section, List<Controls.Control>> groups = new EnumMap<>(Controls.Section.class);
        for (Controls.Control c : Controls.all()) {
            if (!technical && c.tier == Controls.Tier.INTERNAL) continue;
            String hay = fold(c.title + " " + c.hint + " " + c.help + " " + c.spec().fullKey() + " " + c.section.label);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            groups.computeIfAbsent(c.section, k -> new ArrayList<>()).add(c);
        }
        if (groups.isEmpty()) {
            results.addView(hint("Nada encontrado."));
            return;
        }
        for (Map.Entry<Controls.Section, List<Controls.Control>> g : groups.entrySet()) {
            results.addView(Ui.section(activity, t, g.getKey().label));
            LinearLayout card = Ui.card(activity, t);
            for (Controls.Control c : g.getValue()) {
                if (card.getChildCount() > 0) card.addView(Ui.divider(activity, t, t.dp(20)));
                Controls.Surface where = c.on(surface) ? surface : c.surfaces.iterator().next();
                Applicability.Result a = app.on(c.key, where);
                String value = ValueFormat.display(c, st.shown(c.key));
                String sub = surfaces(c) + " · " + value + (a.usable() || a.kind == Applicability.Kind.READ_ONLY ? "" : " · " + a.reason);
                if (technical) sub += "\n" + c.spec().fullKey();
                LinearLayout r = Ui.listRow(activity, t, null, c.title, sub, Controls.editable(c.key) ? Ui.chevron(activity, t) : null);
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

    private TextView hint(String s) {
        TextView v = Ui.text(activity, t, s, Theme.LABEL, t.inkMuted, false);
        v.setPadding(t.dp(8), t.dp(12), t.dp(8), t.dp(12));
        return v;
    }

    static String surfaces(Controls.Control c) {
        if (c.surfaces.size() == 3) return "Todos os ambientes";
        StringBuilder b = new StringBuilder();
        for (Controls.Surface s : c.surfaces) b.append(b.length() == 0 ? "" : " e ").append(s.label);
        return b.toString();
    }
}

package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Build;
import android.widget.LinearLayout;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.*;

/**
 * Styles: save the current look, bring it back, return to the defaults, undo. Each of these
 * changes saved values only; compositor values stay pending until applied, and that is said.
 */
final class PresetsSheet extends Sheet {
    private final Host host;

    PresetsSheet(Host host) {
        super(host.activity(), host.theme(), "Estilos");
        this.host = host;
        AppModel m = host.model();
        LinearLayout card = Ui.card(activity, t);
        String saved = m.repo.preferences().getString("preset.custom", null);
        LinearLayout save = Ui.listRow(activity, t, Icon.STAR, "Salvar estilo atual", saved == null ? "Guarda todos os ajustes deste app" : "Substitui o estilo salvo", null);
        save.setOnClickListener(v -> {
            try {
                m.repo.preferences().edit().putString("preset.custom",
                        PresetCodec.encode(m.repo.overrides(), m.state().enabled, device())).apply();
                host.feedback("Estilo salvo");
                dismiss();
            } catch (Exception e) {
                host.feedback("Não foi possível salvar o estilo");
            }
        });
        card.addView(save);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout load = Ui.listRow(activity, t, Icon.BACKUP, "Usar estilo salvo", saved == null ? "Nenhum estilo salvo ainda" : "Revise as diferenças antes de usar", null);
        Ui.enable(load, saved != null);
        load.setOnClickListener(v -> {
            dismiss();
            PresetReview.open(host, saved);
        });
        card.addView(load);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout defaults = Ui.listRow(activity, t, Icon.RESET, "Voltar ao padrão", "Todos os ambientes e camadas", null);
        defaults.setOnClickListener(v -> confirmDefaults());
        card.addView(defaults);
        card.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout undo = Ui.listRow(activity, t, Icon.UNDO, "Desfazer última alteração", m.repo.canUndo() ? "Volta ao estado anterior à última mudança" : "Nada para desfazer", null);
        Ui.enable(undo, m.repo.canUndo());
        undo.setOnClickListener(v -> {
            host.feedback(m.undo() ? "Alteração desfeita" : "Nada para desfazer");
            dismiss();
        });
        card.addView(undo);
        body().addView(card, Ui.match());
    }

    private void confirmDefaults() {
        dismiss();
        Sheet s = new Sheet(activity, t, "Voltar ao padrão?");
        s.text("Todos os ajustes e camadas voltam aos valores originais do app. Valores definidos pelo"
                + " terminal deixam de valer enquanto o app guardar os seus. Dá para desfazer.");
        s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action("Voltar ao padrão", Ui.ButtonStyle.FILLED, () -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (SettingSpec spec : SettingsRegistry.all())
                if (Controls.editable(spec.key)) m.put(spec.key, spec.defaultValue);
            host.model().repo.replace(m, true);
            host.model().notifyChanged();
            host.feedback("Padrões restaurados");
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
                host.feedback("Arquivo de estilo inválido: " + e.getMessage());
                return;
            }
            AppModel m = host.model();
            ControlState st = m.state();
            Sheet s = new Sheet(host.activity(), t, "Usar este estilo?");
            List<String> lines = new ArrayList<>();
            int compositor = 0;
            for (Map.Entry<String, String> e : parsed.values.entrySet()) {
                Controls.Control c = Controls.get(e.getKey());
                String now = c != null ? st.shown(e.getKey()) : st.shown(e.getKey());
                if (now != null && now.equals(e.getValue())) continue;
                if (c != null) {
                    lines.add(c.title + " · " + surfaces(c) + ": " + ValueFormat.display(c, now) + " → " + ValueFormat.display(c, e.getValue()));
                    if (c.needsRestart()) compositor++;
                } else lines.add(flagName(e.getKey()) + ": " + ("1".equals(e.getValue()) ? "ligado" : "desligado"));
            }
            StringBuilder b = new StringBuilder();
            if (lines.isEmpty()) b.append("Nenhuma diferença em relação aos ajustes atuais.");
            else b.append(lines.size() == 1 ? "1 mudança:\n" : lines.size() + " mudanças:\n");
            for (int i = 0; i < Math.min(40, lines.size()); i++) b.append("• ").append(lines.get(i)).append('\n');
            if (lines.size() > 40) b.append("… e mais ").append(lines.size() - 40).append('\n');
            if (parsed.enabled != st.enabled) b.append("\nO vidro será ").append(parsed.enabled ? "ligado" : "desligado").append('.');
            if (compositor > 0) b.append("\nAjustes da lente dinâmica ficam pendentes até você aplicá-los.");
            if (!parsed.unknown.isEmpty()) b.append("\nIgnorados: ").append(parsed.unknown.size()).append(" itens desconhecidos ou somente leitura.");
            s.text(b.toString().trim());
            s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
            s.action("Usar estilo", Ui.ButtonStyle.FILLED, () -> {
                m.repo.replace(parsed.values, true);
                if (parsed.enabled != m.state().enabled) m.repo.flag(Config.KEY_ENABLED, parsed.enabled);
                m.notifyChanged();
                host.feedback("Estilo aplicado aos ajustes");
                s.dismiss();
            });
            s.show();
        }

        private static String surfaces(Controls.Control c) {
            if (c.surfaces.size() == 3) return "todos";
            StringBuilder b = new StringBuilder();
            for (Controls.Surface s : c.surfaces) b.append(b.length() == 0 ? "" : "/").append(s.label);
            return b.toString();
        }

        static String flagName(String key) {
            String[] p = key.split("\\.");
            Controls.Surface s = Controls.Surface.of(p.length > 1 ? p[1] : "");
            if (p.length == 3) return "Vidro em " + s.label;
            String layer = p.length > 3 ? Controls.layers(s).getOrDefault(p[3], p[3]) : key;
            return s.label + " · camada " + layer;
        }
    }
}

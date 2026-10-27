package io.github.igorcv88.oneuiliquidglass.ui;

import android.text.format.DateFormat;
import android.view.View;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.*;

/**
 * The optional dynamic lens: what state it is in, one next step, the values waiting for a
 * restart, and maintenance. Root is requested only from an explicit tap here; the restart is
 * always confirmed; the helper accepts a fixed command set.
 */
final class LensSheet extends Sheet {
    private final Host host;
    private final AppModel m;
    private final TextView state, detail;
    private final TextView primary;
    private final LinearLayout pendingBox;
    private final TextView protocol, maintenance;
    private final LinearLayout tools;
    private final AppModel.Listener listener = this::bind;

    LensSheet(Host host) {
        super(host.activity(), host.theme(), "Lente dinâmica");
        this.host = host;
        m = host.model();
        subtitle("Refração real das bordas feita pelo compositor do sistema. Opcional; exige root.");
        LinearLayout card = Ui.card(activity, t);
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        state = Ui.text(activity, t, "", Theme.HEADLINE, t.ink, true);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(state);
        detail = Ui.text(activity, t, "", Theme.LABEL, t.inkMuted, false);
        detail.setPadding(0, t.dp(4), 0, t.dp(12));
        card.addView(detail);
        primary = Ui.button(activity, t, "", null, Ui.ButtonStyle.FILLED, false);
        primary.setOnClickListener(v -> next());
        card.addView(primary, Ui.match());
        body().addView(card, Ui.match());
        pendingBox = Ui.column(activity);
        body().addView(pendingBox, Ui.match());

        maintenance = Ui.section(activity, t, "Manutenção");
        body().addView(maintenance);
        tools = Ui.card(activity, t);
        LinearLayout refresh = Ui.listRow(activity, t, Icon.RESTART, "Verificar novamente", "Lê o estado sem alterar nada", null);
        refresh.setOnClickListener(v -> m.lens.status());
        tools.addView(refresh);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout log = Ui.listRow(activity, t, Icon.CODE, "Mostrar registro", "Últimos testes e injeções", null);
        log.setOnClickListener(v -> m.lens.log());
        tools.addView(log);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout copy = Ui.listRow(activity, t, Icon.COPY, "Copiar estado", null, null);
        copy.setOnClickListener(v -> host.copy(describe(m.lens.model())));
        tools.addView(copy);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout remove = Ui.listRow(activity, t, Icon.CLOSE, "Remover a lente", "Apaga só os arquivos dela; reinicie depois", null);
        remove.setOnClickListener(v -> confirmRemove());
        tools.addView(remove);
        body().addView(tools, Ui.match());

        View[] d = Ui.disclosure(activity, t, null, "Detalhes técnicos", null, false, null);
        LinearLayout tech = Ui.card(activity, t);
        tech.addView(d[0]);
        protocol = Ui.text(activity, t, "", Theme.SMALL, t.inkMuted, false);
        protocol.setTextIsSelectable(true);
        protocol.setTypeface(android.graphics.Typeface.MONOSPACE);
        protocol.setPadding(t.dp(18), 0, t.dp(18), t.dp(14));
        ((LinearLayout) d[1]).addView(protocol);
        tech.addView(d[1]);
        body().addView(tech, Ui.match(t.dp(12), 0));
        m.listen(listener);
        onClosed(() -> m.unlisten(listener));
        bind();
    }

    private boolean su() {
        return m.root.granted() && "1".equals(m.lens.model().status.get("helper"));
    }

    private void bind() {
        SfLiveController.Model model = m.lens.model();
        SfLive.State s = SfLive.state(model.status);
        Map<String, String> pending = m.pendingCompositor();
        String title, text, action;
        boolean enabled = !model.busy && !m.root.busy();
        if (!model.bundled) {
            title = "Indisponível nesta versão";
            text = "Este APK foi compilado sem os componentes da lente.";
            action = null;
        } else if (model.busy || m.root.busy()) {
            title = "Trabalhando…";
            text = "Autorize o acesso root se o gerenciador pedir.";
            action = "Aguarde";
            enabled = false;
        } else if (!m.root.granted()) {
            title = "Root não verificado";
            text = "O app só pede acesso root quando você toca abaixo. Nada é instalado nesta etapa.";
            action = "Verificar acesso root";
        } else if (!su()) {
            title = "Estado desconhecido";
            text = "Root concedido. Verifique o estado da lente.";
            action = "Verificar estado";
        } else switch (s) {
            case UNSUPPORTED:
                title = "Firmware não compatível";
                text = "A lente só foi validada em versões específicas do sistema. Nada foi instalado.";
                action = null;
                break;
            case UPDATE:
            case SELFTEST:
                title = s == SfLive.State.UPDATE ? "Não instalada" : "Instalada, falta testar";
                text = "Instalar copia os arquivos e testa a injeção em um processo descartável. Nada é reiniciado.";
                action = s == SfLive.State.UPDATE ? "Instalar e testar" : "Testar";
                break;
            case RESTART:
                title = "Pronta para ativar";
                text = "Ativar reinicia o userspace. Depois de reiniciar o aparelho, a lente fica desligada até você ativá-la de novo.";
                action = "Ativar lente";
                break;
            case LOADED:
                boolean on = "1".equals(model.status.get("sfrefract")) && "1".equals(model.status.get("sf_rewrite"));
                title = on ? "Ativa" : "Carregada, sem refração";
                text = on ? (pending.isEmpty() ? "A lente está carregada no compositor." : "Há ajustes esperando um reinício para valer.")
                        : "A biblioteca está carregada, mas a refração não está ligada.";
                action = !pending.isEmpty() ? (pending.size() == 1 ? "Aplicar 1 ajuste" : "Aplicar " + pending.size() + " ajustes") : on ? null : "Ativar lente";
                break;
            default:
                title = "Sem resposta";
                text = "O auxiliar não respondeu. Confira o acesso root e tente de novo.";
                action = "Verificar acesso root";
        }
        CompositorValues.Outcome last = m.lastApply();
        if (last != CompositorValues.Outcome.NONE && last != CompositorValues.Outcome.UNVERIFIED) {
            String when = DateFormat.getTimeFormat(activity).format(new Date(m.lastApplyAt()));
            switch (last) {
                case CONFIRMED: text += "\nÚltimo reinício (" + when + "): biblioteca carregada com os valores enviados."; break;
                case NOT_LOADED: text += "\nÚltimo reinício (" + when + "): a lente não foi carregada. Veja o registro."; break;
                case CHANGED: text += "\nÚltimo reinício (" + when + "): carregada, mas algum valor mudou depois."; break;
                default: break;
            }
        }
        state.setText(title);
        detail.setText(text);
        primary.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) primary.setText(action);
        primary.setEnabled(enabled);
        pendingBox.removeAllViews();
        if (!pending.isEmpty()) {
            pendingBox.addView(Ui.section(activity, t, "Aguardando reinício"));
            LinearLayout card = Ui.card(activity, t);
            card.setPadding(t.dp(18), t.dp(12), t.dp(18), t.dp(12));
            StringBuilder b = new StringBuilder();
            ControlState st = m.state();
            for (Map.Entry<String, String> e : pending.entrySet()) {
                Controls.Control c = Controls.get(e.getKey());
                if (c == null) continue;
                b.append(b.length() == 0 ? "" : "\n").append(c.title).append(" · ").append(SearchSheet.surfaces(c))
                        .append(": ").append(ValueFormat.display(c, st.device(e.getKey()))).append(" → ")
                        .append(ValueFormat.display(c, e.getValue()));
            }
            card.addView(Ui.text(activity, t, b, Theme.LABEL, t.ink, false));
            pendingBox.addView(card, Ui.match());
        }
        protocol.setText(describe(model));
        // Maintenance needs a helper that answered as root.
        int tools = su() ? View.VISIBLE : View.GONE;
        maintenance.setVisibility(tools);
        this.tools.setVisibility(tools);
    }

    private void next() {
        SfLiveController.Model model = m.lens.model();
        if (model.busy) return;
        if (!m.root.granted()) {
            m.root.check(ok -> {
                if (ok) m.lens.status();
                else host.feedback("Acesso root não concedido");
            });
            return;
        }
        if (!su()) {
            m.lens.status();
            return;
        }
        switch (SfLive.state(model.status)) {
            case UPDATE:
            case SELFTEST:
                m.lens.prepare();
                break;
            case RESTART:
            case LOADED:
                confirmRestart();
                break;
            default:
                m.lens.status();
        }
    }

    /** Explicit, informed confirmation before the userspace restart. */
    private void confirmRestart() {
        SfLiveController.Model model = m.lens.model();
        if (!SfControlPolicy.restart(m.root.granted(), model.bundled, model.busy, model.status)) {
            host.feedback("Instale e teste a lente antes de reiniciar");
            return;
        }
        Map<String, String> pending = m.pendingCompositor();
        Sheet s = new Sheet(activity, t, "Reiniciar userspace?");
        s.text("A tela fica preta por cerca de 20 segundos e todos os apps abertos são fechados, inclusive"
                + " este. O sistema volta com a lente dinâmica"
                + (pending.isEmpty() ? "." : " e " + (pending.size() == 1 ? "1 ajuste novo." : pending.size() + " ajustes novos."))
                + "\n\nSe algo falhar, reiniciar o aparelho volta ao compositor original: nada é carregado na inicialização.");
        Toggle ack = new Toggle(activity, t);
        LinearLayout row = Ui.switchRow(activity, t, null, "Salvei o que estava fazendo", null, ack);
        LinearLayout card = Ui.card(activity, t);
        card.addView(row);
        s.body().addView(card, Ui.match(t.dp(4), 0));
        s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        TextView go = s.action("Reiniciar userspace", Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            m.lens.restartConfirmed(pending);
            host.feedback("Gravando valores e reiniciando…");
        });
        go.setEnabled(false);
        ack.listener((x, on) -> go.setEnabled(on));
        s.show();
    }

    private void confirmRemove() {
        Sheet s = new Sheet(activity, t, "Remover a lente?");
        s.text("Apaga somente os arquivos instalados por este app e desliga a refração. O compositor em"
                + " uso mantém a lente até o próximo reinício do aparelho. Seus ajustes ficam salvos.");
        s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action("Remover", Ui.ButtonStyle.DANGER, () -> {
            s.dismiss();
            if (!su()) {
                host.feedback("Verifique o acesso root primeiro");
                return;
            }
            m.lens.uninstallConfirmed();
        });
        s.show();
    }

    static String describe(SfLiveController.Model model) {
        StringBuilder b = new StringBuilder(model.message == null ? "" : model.message);
        for (Map.Entry<String, String> e : model.status.entrySet())
            b.append('\n').append(e.getKey()).append('=').append(e.getValue());
        if (model.log != null && !model.log.isEmpty()) b.append("\n\n").append(model.log);
        return b.toString().trim();
    }
}

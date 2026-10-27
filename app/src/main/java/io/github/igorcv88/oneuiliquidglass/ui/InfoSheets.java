package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Intent;
import android.provider.Settings;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.TestNotifications;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.List;

/** Secondary sheets: test variants, delivery to the system, values changed outside the app. */
final class InfoSheets {
    private InfoSheets() {}

    static void tests(Host host) {
        testsSheet(host).show();
    }

    static Sheet testsSheet(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        Sheet s = new Sheet(a, t, "Opções de teste");
        LinearLayout card = Ui.card(a, t);
        LinearLayout later = Ui.listRow(a, t, Icon.CLOCK, "Enviar em 5 segundos", "Para ver o pop-up sobre outro app", null);
        later.setOnClickListener(v -> {
            s.dismiss();
            host.requestTest(1, 5000);
        });
        card.addView(later);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout many = Ui.listRow(a, t, Icon.STACK, "Várias na central", "Três notificações empilhadas", null);
        many.setOnClickListener(v -> {
            s.dismiss();
            host.requestTest(3, 0);
        });
        card.addView(many);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout clear = Ui.listRow(a, t, Icon.CLOSE, "Remover notificações de teste",
                TestNotifications.hasScheduled() ? "Também cancela o envio agendado" : "Só as deste app", null);
        clear.setOnClickListener(v -> {
            TestNotifications.clear(a);
            host.feedback("Notificações de teste removidas");
            s.dismiss();
        });
        card.addView(clear);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout channel = Ui.listRow(a, t, Icon.SETTINGS, "Ajustes do canal de teste", "Som, pop-up e importância", Ui.chevron(a, t));
        channel.setOnClickListener(v -> {
            TestNotifications.channel(a);
            a.startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, TestNotifications.CHANNEL));
        });
        card.addView(channel);
        s.body().addView(card, Ui.match());
        TextView help = Ui.text(a, t, "O Android decide se mostra o pop-up. Não perturbe, a importância do"
                + " canal e a opção de pop-ups da Samsung podem impedir; a notificação continua na central.",
                Theme.CAPTION, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(14), t.dp(8), 0);
        s.body().addView(help);
        return s;
    }

    /** Why the system has not confirmed the latest settings, and what to do. */
    static void sync(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        AppModel m = host.model();
        AppModel.Sync sync = m.sync();
        Sheet s = new Sheet(a, t, sync == AppModel.Sync.SYNCED ? "Sistema sincronizado" : "Ajustes sem confirmação");
        String text;
        switch (sync) {
            case NOT_DETECTED:
                text = "O sistema nunca confirmou a leitura dos ajustes. Isso acontece quando o módulo não está"
                        + " ativo no LSPosed, quando o escopo não inclui a interface do sistema ou antes do"
                        + " primeiro reinício depois de ativá-lo.\n\n1. Abra o LSPosed e ative o One UI Liquid Glass.\n"
                        + "2. Confirme o escopo da interface do sistema.\n3. Reinicie o aparelho.";
                break;
            case NO_RESPONSE:
                text = "A interface do sistema leu ajustes antes, mas não confirmou a última alteração. Ela"
                        + " confere a cada 10 segundos; se continuar assim, reiniciar a interface do sistema"
                        + " recarrega o módulo.";
                break;
            case WAITING:
                text = "Alteração enviada há pouco. A confirmação costuma chegar em alguns segundos.";
                break;
            case OFF:
                text = "O vidro está desligado; não há nada para enviar.";
                break;
            default:
                text = "A interface do sistema leu a versão mais recente dos ajustes. Isso confirma a"
                        + " leitura, não a aparência final: o efeito ainda depende do modo, das camadas e do"
                        + " fundo de cada notificação.";
        }
        s.text(text);
        if (sync == AppModel.Sync.NO_RESPONSE || sync == AppModel.Sync.NOT_DETECTED)
            s.action("Reiniciar interface do sistema", Ui.ButtonStyle.TONAL, () -> {
                s.dismiss();
                restartSystemUi(host);
            });
        s.action("Fechar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.show();
    }

    /** SystemUI restart: fixed root command, explicit confirmation, no automation. */
    static void restartSystemUi(Host host) {
        Theme t = host.theme();
        Sheet s = new Sheet(host.activity(), t, "Reiniciar a interface do sistema?");
        s.text("A barra de status, as notificações e a tela de bloqueio somem por alguns segundos e"
                + " voltam. Os apps abertos continuam. Será pedido acesso root para este app.");
        s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action("Reiniciar interface", Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            AppModel m = host.model();
            Runnable restart = () -> m.root.restartSystemUi(ok -> host.feedback(ok ? "Interface do sistema reiniciada" : "Não foi possível reiniciar: " + m.root.message()));
            if (m.root.granted()) restart.run();
            else m.root.check(ok -> {
                if (ok) restart.run();
                else host.feedback("Acesso root não concedido");
            });
        });
        s.show();
    }

    /** "Soft reboot": fixed root command, informed confirmation, no automation. */
    static void softReboot(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        Sheet s = new Sheet(a, t, "Reiniciar userspace?");
        s.text("Reinicia o compositor de tela (SurfaceFlinger) e o zygote, sem desligar o aparelho. A"
                + " tela fica preta por alguns segundos e todos os apps abertos são fechados, inclusive"
                + " este. Os caches de sombreamento são apagados e refeitos.\n\nUma lente dinâmica ativada"
                + " pelo app é descarregada; ative-a de novo depois, se quiser. Será pedido acesso root.");
        Toggle ack = new Toggle(a, t);
        LinearLayout card = Ui.card(a, t);
        card.addView(Ui.switchRow(a, t, null, "Salvei o que estava fazendo", null, ack));
        s.body().addView(card, Ui.match(t.dp(4), 0));
        s.action("Cancelar", Ui.ButtonStyle.OUTLINED, s::dismiss);
        TextView go = s.action("Reiniciar userspace", Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            AppModel m = host.model();
            Runnable restart = () -> m.root.softReboot(ok -> { if (!ok) host.feedback("Não foi possível reiniciar: " + m.root.message()); });
            if (m.root.granted()) restart.run();
            else m.root.check(ok -> {
                if (ok) restart.run();
                else host.feedback("Acesso root não concedido");
            });
        });
        go.setEnabled(false);
        ack.listener((x, on) -> go.setEnabled(on));
        s.show();
    }

    /** A Terminal changed a value the app also saves: choose which one wins. */
    static void conflicts(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        AppModel m = host.model();
        List<String> keys = m.conflicts();
        Sheet s = new Sheet(a, t, "Alterados fora do app");
        if (keys.isEmpty()) {
            s.text("Nenhum ajuste foi alterado fora do app desde o último salvamento.");
            s.action("Fechar", Ui.ButtonStyle.OUTLINED, s::dismiss);
            s.show();
            return;
        }
        s.subtitle("Um terminal mudou estes valores depois que o app os salvou. O app não sobrescreve"
                + " sem perguntar.");
        LinearLayout card = Ui.card(a, t);
        ControlState st = m.state();
        for (String key : keys) {
            Controls.Control c = Controls.get(key);
            if (c == null) continue;
            if (card.getChildCount() > 0) card.addView(Ui.divider(a, t, t.dp(20)));
            LinearLayout block = Ui.column(a);
            block.setPadding(t.dp(18), t.dp(12), t.dp(12), t.dp(8));
            block.addView(Ui.text(a, t, c.title + " · " + SearchSheet.surfaces(c), Theme.BODY, t.ink, false));
            String observed = st.observed.get(key);
            block.addView(Ui.text(a, t, "App: " + ValueFormat.display(c, st.saved.get(key)) + "   Terminal: "
                    + ValueFormat.display(c, observed == null || observed.isEmpty() ? c.spec().defaultValue : observed), Theme.CAPTION, t.inkMuted, false));
            LinearLayout actions = Ui.row(a);
            TextView external = Ui.button(a, t, "Usar o do terminal", null, Ui.ButtonStyle.TEXT, true);
            external.setOnClickListener(v -> {
                m.useExternal(key);
                host.feedback("Usando o valor do terminal");
                s.dismiss();
                conflicts(host);
            });
            TextView keep = Ui.button(a, t, "Manter o do app", null, Ui.ButtonStyle.TEXT, true);
            keep.setOnClickListener(v -> {
                m.keepApp(key);
                host.feedback("Valor do app mantido");
                s.dismiss();
                conflicts(host);
            });
            actions.addView(external);
            actions.addView(keep);
            block.addView(actions);
            card.addView(block);
        }
        s.body().addView(card, Ui.match());
        s.show();
    }
}

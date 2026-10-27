package io.github.igorcv88.oneuiliquidglass.ui;

import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.SfLive;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

/**
 * Ajustes: appearance, the optional dynamic lens, tests, backup, diagnostics and recovery.
 * Everything technical lives here, one tap deeper than the everyday screens.
 */
public final class SettingsPage implements Page {
    private final Host host;
    private final Theme t;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout themeRow, lensRow, syncRow, conflictsRow;
    private final View conflictsDivider;
    private final Toggle motion;

    public SettingsPage(Host host) {
        this.host = host;
        t = host.theme();
        android.app.Activity a = host.activity();
        scroll = Ui.scroller(a);
        content = Ui.column(a);
        scroll.addView(content);
        content.addView(Ui.title(a, t, "Ajustes"), Ui.match(t.dp(8), t.dp(4)));

        content.addView(Ui.section(a, t, "Aparência"));
        LinearLayout look = Ui.card(a, t);
        themeRow = Ui.listRow(a, t, Icon.SUN, "Tema", "", Ui.chevron(a, t));
        themeRow.setOnClickListener(v -> chooseTheme());
        look.addView(themeRow);
        look.addView(Ui.divider(a, t, t.dp(72)));
        motion = new Toggle(a, t);
        LinearLayout motionRow = Ui.switchRow(a, t, Icon.PULSE, "Reduzir movimento", "Menos animações no app", motion);
        motion.listener((x, on) -> {
            host.model().ui.edit().putBoolean("reduceMotion", on).apply();
            host.rebuild();
        });
        look.addView(motionRow);
        content.addView(look, Ui.match());

        content.addView(Ui.section(a, t, "Vidro"));
        LinearLayout glass = Ui.card(a, t);
        lensRow = Ui.listRow(a, t, Icon.LENS, "Lente dinâmica", "", Ui.chevron(a, t));
        lensRow.setOnClickListener(v -> host.openLens());
        glass.addView(lensRow);
        glass.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout tests = Ui.listRow(a, t, Icon.BELL, "Notificação de teste", "Agendar, várias de uma vez, canal", Ui.chevron(a, t));
        tests.setOnClickListener(v -> host.openTestOptions());
        glass.addView(tests);
        glass.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout styles = Ui.listRow(a, t, Icon.PALETTE, "Estilos", "Salvar, restaurar, desfazer", Ui.chevron(a, t));
        styles.setOnClickListener(v -> new PresetsSheet(host).show());
        glass.addView(styles);
        content.addView(glass, Ui.match());

        content.addView(Ui.section(a, t, "Backup"));
        LinearLayout backup = Ui.card(a, t);
        LinearLayout export = Ui.listRow(a, t, Icon.BACKUP, "Exportar ajustes", "Arquivo JSON com todos os valores do app", null);
        export.setOnClickListener(v -> host.exportSettings());
        backup.addView(export);
        backup.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout imp = Ui.listRow(a, t, Icon.COPY, "Importar ajustes", "Mostra as diferenças antes de usar", null);
        imp.setOnClickListener(v -> host.importSettings());
        backup.addView(imp);
        content.addView(backup, Ui.match());

        content.addView(Ui.section(a, t, "Diagnóstico"));
        LinearLayout diag = Ui.card(a, t);
        syncRow = Ui.listRow(a, t, Icon.PULSE, "Comunicação com o sistema", "", Ui.chevron(a, t));
        syncRow.setOnClickListener(v -> host.openSync());
        diag.addView(syncRow);
        conflictsDivider = Ui.divider(a, t, t.dp(72));
        diag.addView(conflictsDivider);
        conflictsRow = Ui.listRow(a, t, Icon.WARNING, "Alterados fora do app", "", Ui.chevron(a, t));
        conflictsRow.setOnClickListener(v -> host.openConflicts());
        diag.addView(conflictsRow);
        diag.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout all = Ui.listRow(a, t, Icon.CODE, "Todos os parâmetros", "Lista técnica completa, com busca", Ui.chevron(a, t));
        all.setOnClickListener(v -> new SearchSheet(host, Controls.Surface.HEADSUP, true).show());
        diag.addView(all);
        diag.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout report = Ui.listRow(a, t, Icon.COPY, "Copiar relatório", "Sem conteúdo de notificações", null);
        report.setOnClickListener(v -> host.copy(report()));
        diag.addView(report);
        content.addView(diag, Ui.match());

        content.addView(Ui.section(a, t, "Recuperação"));
        LinearLayout rec = Ui.card(a, t);
        LinearLayout safe = Ui.listRow(a, t, Icon.SHIELD, "Desligar todo o vidro", "Volta ao visual original; os ajustes ficam salvos", null);
        safe.setOnClickListener(v -> {
            host.model().master(false);
            host.feedback("Vidro desligado");
        });
        rec.addView(safe);
        rec.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout restart = Ui.listRow(a, t, Icon.RESTART, "Reiniciar interface do sistema", "Reinicia a SystemUI; os apps continuam abertos", null);
        restart.setOnClickListener(v -> InfoSheets.restartSystemUi(host));
        rec.addView(restart);
        rec.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout soft = Ui.listRow(a, t, Icon.RESTART, "Reiniciar userspace (\u201csoft reboot\u201d)", "Reinicia o compositor e o zygote; todos os apps fecham", null);
        soft.setOnClickListener(v -> InfoSheets.softReboot(host));
        rec.addView(soft);
        content.addView(rec, Ui.match());
        TextView help = Ui.text(a, t, "Se as notificações travarem, desative o módulo no LSPosed e reinicie o"
                + " aparelho. A lente dinâmica nunca é carregada na inicialização: reiniciar o aparelho volta"
                + " ao compositor original.", Theme.CAPTION, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(12), t.dp(8), 0);
        content.addView(help);
        TextView version = Ui.text(a, t, "One UI Liquid Glass " + versionName(a), Theme.CAPTION, t.inkFaint, false);
        version.setPadding(t.dp(8), t.dp(16), t.dp(8), 0);
        content.addView(version);
    }

    private static String versionName(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void chooseTheme() {
        Sheet s = new Sheet(host.activity(), t, "Tema");
        s.choices(new String[][] {{"system", "Seguir o sistema", null}, {"light", "Claro", null}, {"dark", "Escuro", null}},
                host.model().ui.getString("theme", "system"), v -> {
                    host.model().ui.edit().putString("theme", v).apply();
                    host.rebuild();
                });
        s.show();
    }

    private String report() {
        AppModel m = host.model();
        ControlState st = m.state();
        StringBuilder r = new StringBuilder("One UI Liquid Glass ").append(versionName(host.activity()))
                .append('\n').append(PresetsSheet.device())
                .append("\nVidro: ").append(st.enabled ? "ligado" : "desligado")
                .append(" · modo: ").append(st.applicability().mode.label)
                .append(" · sistema: ").append(m.sync())
                .append(" · leitura de propriedades: ").append(m.observedAvailable() ? "ok" : "indisponível")
                .append(" · preferências LSPosed: ").append(Config.lsposedPrefs)
                .append("\nLente: ").append(SfLive.state(m.lens.model().status)).append('\n');
        for (SettingSpec s : SettingsRegistry.all()) {
            String saved = st.saved.get(s.key), observed = st.observed.get(s.key);
            r.append(s.fullKey()).append(" padrão=").append(s.defaultValue)
                    .append(" salvo=").append(saved == null ? "-" : saved)
                    .append(" aparelho=").append(observed == null || observed.isEmpty() ? "-" : observed)
                    .append(" em_uso=").append(st.device(s.key))
                    .append(s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE && st.pendingRestart(s.key) ? " pendente" : "")
                    .append('\n');
        }
        for (java.util.Map.Entry<String, String> e : st.shownMap().entrySet())
            if (e.getKey().startsWith("surface.")) r.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return r.toString();
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public ScrollView scroller() {
        return scroll;
    }

    @Override
    public void insets(int top, int bottom) {
        content.setPadding(t.dp(16), top + t.dp(12), t.dp(16), bottom);
    }

    @Override
    public void bind() {
        AppModel m = host.model();
        String theme = m.ui.getString("theme", "system");
        Ui.setSubtitle(themeRow, theme.equals("light") ? "Claro" : theme.equals("dark") ? "Escuro" : "Seguir o sistema");
        motion.set(m.ui.getBoolean("reduceMotion", false), false);
        Mode mode = m.mode();
        SfLive.State lens = SfLive.state(m.lens.model().status);
        String lensText = mode == Mode.DYNAMIC_LENS ? "Ativa" : !m.lens.model().bundled ? "Indisponível nesta versão"
                : lens == SfLive.State.NO_ROOT ? "Opcional · exige root" : lens == SfLive.State.UNSUPPORTED ? "Firmware não compatível" : "Não ativada";
        int pending = mode == Mode.DYNAMIC_LENS ? m.pendingCompositor().size() : 0;
        if (pending > 0) lensText += pending == 1 ? " · 1 ajuste pendente" : " · " + pending + " ajustes pendentes";
        Ui.setSubtitle(lensRow, lensText);
        switch (m.sync()) {
            case SYNCED: Ui.setSubtitle(syncRow, "Ajustes lidos pelo sistema"); break;
            case WAITING: Ui.setSubtitle(syncRow, "Enviando…"); break;
            case NO_RESPONSE: Ui.setSubtitle(syncRow, "Sem confirmação da última alteração"); break;
            case NOT_DETECTED: Ui.setSubtitle(syncRow, "Módulo não detectado"); break;
            default: Ui.setSubtitle(syncRow, "Vidro desligado");
        }
        int conflicts = m.conflicts().size();
        conflictsRow.setVisibility(conflicts == 0 ? View.GONE : View.VISIBLE);
        conflictsDivider.setVisibility(conflicts == 0 ? View.GONE : View.VISIBLE);
        Ui.setSubtitle(conflictsRow, conflicts == 1 ? "1 ajuste" : conflicts + " ajustes");
    }
}

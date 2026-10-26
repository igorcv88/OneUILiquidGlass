package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.*;
import android.view.View;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.live.*;

import java.util.Map;
import java.util.function.Consumer;

/**
 * View of the bundled SfLive protocol. Installs files, not a KernelSU module; restart is separate.
 */
public final class ModulePanel {
    private final Activity activity;
    private final SfLiveController controller;
    private final RootAccess root;
    private TextView rootStatus;
    private Button grant;
    private boolean light;
    private final Consumer<String> copy;
    private TextView status;
    private Button primary, refresh, log, uninstall, recompile;

    public ModulePanel(
            Activity activity,
            SfLiveController controller,
            RootAccess root,
            Consumer<String> copy) {
        this.activity = activity;
        this.controller = controller;
        this.root = root;
        this.copy = copy;
    }

    public void attach(LinearLayout parent, boolean light, int ink, int muted, int card) {
        this.light = light;
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackground(UiStyle.pane(activity, light, true, 24));
        UiStyle.space(panel, 16);
        parent.addView(panel);
        TextView title = new TextView(activity);
        title.setText("Refração dinâmica");
        title.setTextSize(20);
        title.setTextColor(ink);
        panel.addView(title);
        TextView intro = new TextView(activity);
        intro.setTextColor(muted);
        intro.setTextSize(13);
        intro.setText(
                "Ative a lente real das notificações pelo SurfaceFlinger. O componente já está no"
                    + " APK. A instalação e a injeção exigem root; o reinício só acontece após sua"
                    + " confirmação.");
        panel.addView(intro);
        rootStatus = new TextView(activity);
        rootStatus.setTextColor(muted);
        panel.addView(rootStatus);
        grant =
                add(
                        panel,
                        "Conceder / verificar acesso root",
                        () ->
                                root.check(
                                        ok -> {
                                            if (ok) controller.status();
                                        }));
        status = new TextView(activity);
        status.setTextColor(ink);
        status.setTextSize(13);
        status.setTextIsSelectable(true);
        panel.addView(status);
        primary = add(panel, "Injetar refração no SurfaceFlinger", this::primary);
        primary.setEnabled(false);
        UiStyle.button(primary, light, true);
        Button expand = add(panel, "⌄  Detalhes e manutenção", () -> {});
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        panel.addView(details);
        expand.setOnClickListener(
                v -> {
                    boolean open = details.getVisibility() != View.VISIBLE;
                    details.setVisibility(open ? View.VISIBLE : View.GONE);
                    expand.setText(open ? "⌃  Recolher detalhes" : "⌄  Detalhes e manutenção");
                });
        TextView protocol = new TextView(activity);
        protocol.setTextColor(muted);
        protocol.setTextIsSelectable(true);
        details.addView(protocol);
        refresh = add(details, "Atualizar estado (SU)", controller::status);
        recompile = add(details, "Reiniciar para recompilar SF (avançado)", this::confirmRestart);
        log = add(details, "Mostrar log do helper", controller::log);
        add(details, "Copiar estado / log", () -> copy.accept(describe(controller.model())));
        uninstall =
                add(
                        details,
                        "Remover arquivos da injeção",
                        () ->
                                new AlertDialog.Builder(activity)
                                        .setTitle("Remover componente SF?")
                                        .setMessage(
                                                "Remove somente os arquivos e caches documentados"
                                                    + " pelo helper e limpa o modo de refração. O"
                                                    + " SF atual pode continuar com a biblioteca em"
                                                    + " memória até reiniciar. Faça reboot depois"
                                                    + " para retornar ao compositor nativo. Os"
                                                    + " presets do APK são preservados.")
                                        .setNegativeButton("Cancelar", null)
                                        .setPositiveButton(
                                                "Remover",
                                                (d, w) -> controller.uninstallConfirmed())
                                        .show());
        Runnable update =
                () -> {
                    SfLiveController.Model model = controller.model();
                    SfLive.State state = SfLive.state(model.status);
                    boolean su = root.granted() && "1".equals(model.status.get("helper"));
                    boolean compatible =
                            SfControlPolicy.prepare(
                                    root.granted(), model.bundled, model.busy, model.status);
                    rootStatus.setText(root.message());
                    grant.setEnabled(!root.busy() && !model.busy);
                    grant.setText(
                            root.granted()
                                    ? "Root autorizado · verificar novamente"
                                    : "Conceder / verificar acesso root");
                    primary.setEnabled(compatible);
                    refresh.setEnabled(root.granted() && model.bundled && !model.busy);
                    log.setEnabled(su && !model.busy);
                    uninstall.setEnabled(su && !model.busy);
                    recompile.setEnabled(
                            SfControlPolicy.restart(
                                    root.granted(), model.bundled, model.busy, model.status));
                    protocol.setText(describe(model));
                    String next;
                    if (!model.bundled) next = "Componente indisponível neste APK";
                    else if (model.busy) next = "Verificando / preparando…";
                    else if (!su) next = "Injetar refração · conceda root primeiro";
                    else
                        switch (state) {
                            case UPDATE:
                                next = "1. Instalar refração e testar";
                                break;
                            case SELFTEST:
                                next = "1. Testar componente de refração";
                                break;
                            case RESTART:
                                next = "2. Injetar e ativar refração";
                                break;
                            case LOADED:
                                next =
                                        active(model.status)
                                                ? "Biblioteca carregada · verificar estado"
                                                : "2. Injetar e ativar refração";
                                break;
                            case UNSUPPORTED:
                                next = "Firmware incompatível";
                                break;
                            default:
                                next = "Injetar refração · verificar root";
                        }
                    primary.setText(next);
                    status.setText(
                            model.busy
                                    ? model.message
                                    : !su
                                            ? "Botão bloqueado até confirmar acesso SU. Nenhuma"
                                                    + " injeção foi iniciada."
                                            : state == SfLive.State.RESTART
                                                    ? "Pronto para injetar. A próxima etapa pede"
                                                            + " confirmação de reinício."
                                                    : state == SfLive.State.LOADED
                                                            ? "Biblioteca mapeada. A execução"
                                                                    + " óptica do shader ainda"
                                                                    + " precisa ser verificada."
                                                            : state == SfLive.State.UNSUPPORTED
                                                                    ? "O firmware não está na lista"
                                                                            + " de compatibilidade."
                                                                    : model.message);
                    status.setPadding(0, UiStyle.dp(activity, 10), 0, UiStyle.dp(activity, 10));
                };
        controller.listen(model -> update.run());
        root.listen(update);
    }

    private boolean active(Map<String, String> status) {
        return "1".equals(status.get("sf_rewrite")) && "1".equals(status.get("sfrefract"));
    }

    private void primary() {
        SfLiveController.Model model = controller.model();
        if (model.busy
                || !model.bundled
                || !root.granted()
                || !"1".equals(model.status.get("helper"))) return;
        switch (SfLive.state(model.status)) {
            case UPDATE:
            case SELFTEST:
                controller.prepare();
                break;
            case RESTART:
                confirmRestart();
                break;
            case LOADED:
                if (active(model.status)) controller.status();
                else confirmRestart();
                break;
            case UNSUPPORTED:
                break;
            default:
                controller.status();
        }
    }

    private void confirmRestart() {
        SfLiveController.Model model = controller.model();
        if (!SfControlPolicy.restart(root.granted(), model.bundled, model.busy, model.status))
            return;
        EditText input = new EditText(activity);
        input.setHint("Digite REINICIAR");
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle("Ativar refração e reiniciar userspace?")
                        .setMessage(
                                "A tela ficará preta e os apps abertos serão encerrados. O helper"
                                    + " reinicia SurfaceFlinger e zygote; o retorno observado foi"
                                    + " de cerca de 20 s, mas pode demorar mais ou falhar. O"
                                    + " selftest será conferido novamente. Nenhuma injeção é feita"
                                    + " no boot: reboot volta ao compositor nativo. O helper"
                                    + " integrado ao APK ainda precisa de teste real. Parâmetros"
                                    + " sf.* desejados exigem primeiro os comandos manuais do"
                                    + " editor; este reinício recompila os valores observados nas"
                                    + " props, não grava os presets no compositor.\n\n"
                                    + "Digite REINICIAR para confirmar.")
                        .setView(input)
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Reiniciar e aplicar", null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        v -> {
                                            if (!input.getText().toString().equals("REINICIAR")) {
                                                input.setError("Digite REINICIAR");
                                                return;
                                            }
                                            dialog.dismiss();
                                            controller.restartConfirmed();
                                        }));
        dialog.show();
    }

    private Button add(LinearLayout panel, String title, Runnable action) {
        Button b = new Button(activity);
        b.setText(title);
        UiStyle.button(b, light, false);
        UiStyle.space(b, 4);
        b.setOnClickListener(v -> action.run());
        panel.addView(b);
        return b;
    }

    private String describe(SfLiveController.Model model) {
        if (model.status.isEmpty()) return model.message;
        Map<String, String> s = model.status;
        StringBuilder text =
                new StringBuilder(model.message)
                        .append("\n\nPróxima etapa: ")
                        .append(SfLive.state(s))
                        .append("\nBundle: ")
                        .append(s.getOrDefault("bundled_version", "?"))
                        .append(" · instalado: ")
                        .append(s.getOrDefault("installed_version", "nenhum"))
                        .append("\nFirmware permitido: ")
                        .append(s.getOrDefault("fingerprint_ok", "?"))
                        .append(" · conflito legado: ")
                        .append(s.getOrDefault("legacy_conflict", "?"))
                        .append(" · hashes atuais: ")
                        .append(s.getOrDefault("installed_ok", "?"))
                        .append("\nSelftest válido: ")
                        .append(s.getOrDefault("selftest_ok", "?"))
                        .append(" · biblioteca atual mapeada: ")
                        .append(s.getOrDefault("sf_loaded", "?"))
                        .append("\nReescrita habilitada: ")
                        .append(s.getOrDefault("sf_rewrite", "?"))
                        .append(" · sfrefract: ")
                        .append(s.getOrDefault("sfrefract", "?"))
                        .append("\nMarcador óptico: ")
                        .append(s.getOrDefault("sf_optics", "?"))
                        .append(
                                "\n"
                                    + "Efetividade do shader: não confirmada; sf_loaded e sf.optics"
                                    + " não substituem REWRITE_OK.");
        if ("1".equals(s.get("legacy_conflict")))
            text.append(
                    "\n"
                        + "Desative o módulo legado oulg_sf pelo seu método de root e faça reboot"
                        + " antes de usar a rota live.");
        return text.toString();
    }
}

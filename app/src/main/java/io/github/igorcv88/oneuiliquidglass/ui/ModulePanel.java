package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.*;
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
    private final Consumer<String> copy;
    private TextView status;
    private Button primary, refresh, log, uninstall, recompile;

    public ModulePanel(Activity activity, SfLiveController controller, Consumer<String> copy) {
        this.activity = activity;
        this.controller = controller;
        this.copy = copy;
    }

    public void attach(LinearLayout parent, boolean light, int ink, int muted, int card) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(1);
        int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(card);
        parent.addView(panel);
        TextView title = new TextView(activity);
        title.setText("Refração SurfaceFlinger");
        title.setTextSize(20);
        title.setTextColor(ink);
        panel.addView(title);
        TextView intro = new TextView(activity);
        intro.setTextColor(muted);
        intro.setTextSize(13);
        intro.setText(
                "Os binários desta versão estão no APK. Sem download, ZIP ou flash de módulo."
                    + " Somente verificar, instalar, testar e reiniciar pelo helper exigem SU para"
                    + " este APK. Nenhum root é pedido ao abrir a tela.");
        panel.addView(intro);
        status = new TextView(activity);
        status.setTextColor(ink);
        status.setTextSize(13);
        status.setTextIsSelectable(true);
        panel.addView(status);
        primary = add(panel, "Verificar SurfaceFlinger / autorizar SU", this::primary);
        refresh = add(panel, "Atualizar estado (SU)", controller::status);
        recompile = add(panel, "Reiniciar para recompilar SF (avançado)", this::confirmRestart);
        log = add(panel, "Mostrar log do helper", controller::log);
        add(panel, "Copiar estado / log", () -> copy.accept(describe(controller.model())));
        uninstall =
                add(
                        panel,
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
        controller.listen(
                model -> {
                    status.setText(describe(model));
                    SfLive.State state = SfLive.state(model.status);
                    primary.setEnabled(
                            model.bundled
                                    && !model.busy
                                    && !(state == SfLive.State.UNSUPPORTED)
                                    && !"0".equals(model.status.get("bundled_ok"))
                                    && !"1".equals(model.status.get("legacy_conflict")));
                    recompile.setEnabled(
                            !model.busy
                                    && "1".equals(model.status.get("selftest_ok"))
                                    && "0".equals(model.status.get("update_needed")));
                    refresh.setEnabled(model.bundled && !model.busy);
                    log.setEnabled(model.bundled && !model.busy);
                    uninstall.setEnabled(model.bundled && !model.busy);
                    if (!model.bundled) {
                        primary.setText("Bundle indisponível neste APK");
                        return;
                    }
                    if (model.busy) {
                        primary.setText("Aguarde…");
                        return;
                    }
                    switch (state) {
                        case UPDATE:
                            primary.setText("Instalar / atualizar e executar selftest");
                            break;
                        case SELFTEST:
                            primary.setText("Executar selftest");
                            break;
                        case RESTART:
                            primary.setText("Ativar refração · reiniciar SF e zygote");
                            break;
                        case LOADED:
                            primary.setText(
                                    active(model.status)
                                            ? "Biblioteca atual carregada · verificar"
                                            : "Ativar refração · reiniciar SF e zygote");
                            break;
                        case UNSUPPORTED:
                            primary.setText("Firmware fora da allowlist");
                            break;
                        default:
                            primary.setText("Verificar SurfaceFlinger / autorizar SU");
                    }
                });
    }

    private boolean active(Map<String, String> status) {
        return "1".equals(status.get("sf_rewrite")) && "1".equals(status.get("sfrefract"));
    }

    private void primary() {
        SfLiveController.Model model = controller.model();
        if (model.busy || !model.bundled) return;
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
        b.setAllCaps(false);
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
        return text.toString();
    }
}

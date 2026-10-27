package io.github.igorcv88.oneuiliquidglass.live;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Activity-scoped controller. No root request on app startup, rendering, resume, slider or preset.
 */
public final class SfLiveController {
    public static final class Model {
        public final boolean busy, bundled;
        public final Map<String, String> status;
        public final String message, log;

        Model(
                boolean busy,
                boolean bundled,
                Map<String, String> status,
                String message,
                String log) {
            this.busy = busy;
            this.bundled = bundled;
            this.status = Collections.unmodifiableMap(new LinkedHashMap<>(status));
            this.message = message;
            this.log = log;
        }
    }

    private final Activity activity;
    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Model model;
    private Consumer<Model> listener;
    private boolean closed;

    public SfLiveController(Activity activity, ExecutorService worker) {
        this.activity = activity;
        this.worker = worker;
        boolean bundled = SfLive.bundled(activity);
        model =
                new Model(
                        false,
                        bundled,
                        Collections.emptyMap(),
                        bundled
                                ? "Bundle desta versão disponível. Toque para verificar/autorizar"
                                      + " SU. Ainda não foi solicitado root."
                                : "Bundle SF ausente neste build. Compile com sfhook/build.sh antes"
                                      + " do Gradle. Nenhuma operação root está disponível.",
                        "");
    }

    public Model model() {
        return model;
    }

    public void listen(Consumer<Model> listener) {
        this.listener = listener;
        listener.accept(model);
    }

    public void close() {
        closed = true;
        listener = null;
        main.removeCallbacksAndMessages(null);
    }

    private void publish(Model next) {
        main.post(
                () -> {
                    if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                    model = next;
                    if (listener != null) listener.accept(next);
                });
    }

    private SfLiveFlow.Reply run(File bundle, SfLive.Command command, String mode)
            throws Exception {
        return run(SfLive.command(bundle.getAbsolutePath(), command, mode), command);
    }

    private SfLiveFlow.Reply run(List<String> argv, SfLive.Command command) throws Exception {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        Future<String> output =
                outputReader.submit(
                        () -> {
                            try (InputStream in = process.getInputStream()) {
                                return new String(
                                        in.readNBytes(131072),
                                        java.nio.charset.StandardCharsets.UTF_8);
                            }
                        });
        try {
            long timeout =
                    command == SfLive.Command.SELFTEST
                            ? 75
                            : command == SfLive.Command.INSTALL ? 30 : 15;
            if (!process.waitFor(timeout, TimeUnit.SECONDS))
                throw new IOException(
                        "Tempo esgotado em "
                                + command
                                + ". Verifique autorização SU e leia o log antes de repetir.");
            return new SfLiveFlow.Reply(process.exitValue(), output.get(2, TimeUnit.SECONDS));
        } finally {
            process.destroy();
            outputReader.shutdownNow();
        }
    }

    private File verifiedBundle() throws Exception {
        File dir = SfLive.extract(activity);
        Map<String, String> manifest = SfLive.manifest(activity);
        if (!"arm64-v8a".equals(manifest.get("abi")))
            throw new IOException("ABI do bundle incompatível");
        for (String[] file :
                new String[][] {
                    {"oulg_inject", "sha256_inject"},
                    {"liboulg_sf.so", "sha256_lib"},
                    {SfLive.HELPER, "sha256_helper"}
                }) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = new FileInputStream(new File(dir, file[0]))) {
                byte[] bytes = new byte[8192];
                int n;
                while ((n = stream.read(bytes)) != -1) digest.update(bytes, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equals(manifest.get(file[1])))
                throw new IOException("Hash inválido: " + file[0] + ". Helper não foi executado.");
        }
        return dir;
    }

    private enum Action {
        STATUS,
        PREPARE,
        RESTART,
        LOG,
        UNINSTALL
    }

    public void status() {
        submit(Action.STATUS);
    }

    public void prepare() {
        submit(Action.PREPARE);
    }

    /**
     * Writes {@code compositorValues} (pending sf.* values, possibly empty), confirms them and
     * restarts. Recorded so the next launch can check what the new compositor compiled with.
     */
    public void restartConfirmed(Map<String, String> compositorValues) {
        restartValues = new LinkedHashMap<>(compositorValues);
        submit(Action.RESTART);
    }

    private Map<String, String> restartValues = Collections.emptyMap();

    public void log() {
        submit(Action.LOG);
    }

    public void uninstallConfirmed() {
        submit(Action.UNINSTALL);
    }

    private void submit(Action action) {
        if (closed || model.busy || !model.bundled) return;
        Model before = model;
        model =
                new Model(
                        true,
                        true,
                        before.status,
                        "Executando "
                                + action
                                + ". Autorize SU para o UID deste APK pelo método de root"
                                + " instalado.",
                        before.log);
        if (listener != null) listener.accept(model);
        worker.execute(
                () -> {
                    File bundle = null;
                    try {
                        bundle = verifiedBundle();
                        final File extracted = bundle;
                        SfLiveFlow flow =
                                new SfLiveFlow(
                                        new SfLiveFlow.Runner() {
                                            @Override
                                            public SfLiveFlow.Reply run(
                                                    SfLive.Command command, String mode)
                                                    throws Exception {
                                                return SfLiveController.this.run(
                                                        SfLive.command(
                                                                extracted.getAbsolutePath(),
                                                                command,
                                                                mode),
                                                        command);
                                            }

                                            @Override
                                            public SfLiveFlow.Reply setprops(
                                                    Map<String, String> values)
                                                    throws Exception {
                                                return SfLiveController.this.run(
                                                        SfLive.setprops(
                                                                extracted.getAbsolutePath(),
                                                                values),
                                                        SfLive.Command.SETPROPS);
                                            }
                                        });
                        Map<String, String> status = before.status;
                        String message, log = before.log;
                        switch (action) {
                            case PREPARE:
                                status = flow.prepare();
                                message =
                                        "Preparação concluída. Nenhum reinício foi feito. Confira o"
                                            + " estado abaixo.";
                                break;
                            case RESTART:
                                Map<String, String> values = restartValues;
                                // Committed before the restart kills this process.
                                CompositorValues.recordAttempt(
                                        activity.getSharedPreferences(
                                                "sf_live_ui", Activity.MODE_PRIVATE),
                                        values,
                                        System.currentTimeMillis());
                                flow.applyAndRestart(values, written ->
                                        new io.github.igorcv88.oneuiliquidglass.config.ConfigRepository(activity)
                                                .adoptWritten(written));
                                message =
                                        "Reinício iniciado. Os apps serão encerrados. Reabra e"
                                            + " verifique o estado; ainda não é confirmação de"
                                            + " injeção/refração.";
                                status = Collections.emptyMap();
                                break;
                            case LOG:
                                SfLiveFlow.Reply reply = run(bundle, SfLive.Command.LOG, null);
                                log = reply.output;
                                message = "Últimos logs de selftest/injeção.\n" + log;
                                break;
                            case UNINSTALL:
                                SfLiveFlow.Reply removed =
                                        run(bundle, SfLive.Command.UNINSTALL, null);
                                if (removed.exit != 0
                                        || !"ok".equals(removed.values().get("uninstall")))
                                    throw new IOException(removed.output);
                                status = flow.status();
                                message =
                                        "Arquivos removidos. A biblioteca em memória só sai quando"
                                            + " o SF reiniciar; faça reboot para retornar ao"
                                            + " compositor nativo.";
                                break;
                            default:
                                status = flow.status();
                                message =
                                        "Estado lido do helper. Biblioteca mapeada não comprova"
                                            + " REWRITE_OK.";
                        }
                        io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.refresh();
                        publish(new Model(false, true, status, message, log));
                    } catch (Exception error) {
                        if (action == Action.RESTART)
                            CompositorValues.clearAttempt(
                                    activity.getSharedPreferences(
                                            "sf_live_ui", Activity.MODE_PRIVATE));
                        String log = before.log;
                        if (error instanceof SfLiveFlow.Failure
                                && ((SfLiveFlow.Failure) error).selftest
                                && bundle != null) {
                            try {
                                log = run(bundle, SfLive.Command.LOG, null).output;
                            } catch (Exception secondary) {
                                log = "Falha ao ler log: " + secondary.getMessage();
                            }
                        }
                        publish(
                                new Model(
                                        false,
                                        true,
                                        Collections.emptyMap(),
                                        "Operação interrompida: "
                                                + error.getMessage()
                                                + "\nNenhum reinício adicional será feito."
                                                + (log.isEmpty() ? "" : "\n" + log),
                                        log));
                    }
                });
    }
}

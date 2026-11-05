package io.github.igorcv88.oneuiliquidglass.live;

import android.app.Activity;
import io.github.igorcv88.oneuiliquidglass.R;
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
        public final boolean busy, bundled, failed;
        public final Map<String, String> status;
        public final String message, log;

        Model(
                boolean busy,
                boolean bundled,
                Map<String, String> status,
                String message,
                String log) {
            this(busy, bundled, status, message, log, false);
        }

        Model(boolean busy, boolean bundled, Map<String, String> status, String message, String log, boolean failed) {
            this.failed = failed;
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
                                ? activity.getString(R.string.sf_bundle_ready)
                                : activity.getString(R.string.sf_bundle_missing),
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
                        activity.getString(R.string.sf_command_timeout, command.name()));
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
            throw new IOException(activity.getString(R.string.sf_bad_abi));
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
                throw new IOException(activity.getString(R.string.sf_bad_hash, file[0]));
        }
        return dir;
    }

    private String localizedFailure(Exception error) {
        String text = error.getMessage();
        if (!(error instanceof SfLiveFlow.Failure) || text == null) return String.valueOf(text);
        int newline = text.indexOf('\n');
        String first = newline < 0 ? text : text.substring(0, newline);
        String tail = newline < 0 ? "" : text.substring(newline);
        int id;
        switch (first) {
            case "Helper sem resposta válida; root não confirmado.": id = R.string.sf_failure_helper; break;
            case "O módulo legado oulg_sf ainda está ativo. Desative pelo método de root existente e faça reboot antes da rota live.": id = R.string.sf_failure_legacy; break;
            case "Firmware fora da allowlist do bundle. Nada foi instalado/reiniciado.": id = R.string.sf_failure_firmware; break;
            case "Bundle inválido. Nada foi instalado/reiniciado.": id = R.string.sf_failure_bundle; break;
            case "Instalação não confirmada.": id = R.string.sf_failure_install; break;
            case "Instalação difere do bundle. Operação interrompida.": id = R.string.sf_failure_mismatch; break;
            case "Instalação inconsistente; prepare novamente.": id = R.string.sf_failure_inconsistent; break;
            case "Selftest não aprovado.": id = R.string.sf_failure_selftest; break;
            case "Stamp do selftest não corresponde aos binários instalados.": id = R.string.sf_failure_stamp; break;
            case "Valores recusados; nada foi reiniciado.": id = R.string.sf_failure_values; break;
            case "Binários mudaram ou selftest ausente. Prepare novamente antes de reiniciar.": id = R.string.sf_failure_not_ready; break;
            case "Início do reinício não confirmado.": id = R.string.sf_failure_restart; break;
            default:
                if (first.startsWith("O valor de ") && first.endsWith(" não foi confirmado; nada foi reiniciado.")) {
                    String key = first.substring("O valor de ".length(), first.indexOf(" não foi confirmado;"));
                    return activity.getString(R.string.sf_failure_not_confirmed, key) + tail;
                }
                int rejected = first.indexOf(" recusado (");
                if (rejected > 0 && first.endsWith(")"))
                    return activity.getString(R.string.sf_failure_command, first.substring(0, rejected),
                            first.substring(rejected + " recusado (".length(), first.length() - 1)) + tail;
                return text;
        }
        return activity.getString(id) + tail;
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

    /** Only an observed, loaded new SF process can establish the compiled-input baseline. */
    private void confirmCompiled(Map<String, String> status) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(
                "sf_live_ui", Activity.MODE_PRIVATE);
        long requested = prefs.getLong("restartRequestedAt", 0);
        if (!io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.available()
                || requested <= prefs.getLong("compiledAt", 0)
                || !"1".equals(status.get("sf_rewrite"))) return;
        Map<String, String> inputs = CompositorValues.attemptValues(prefs);
        if (CompositorValues.verify(requested, inputs, status,
                io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.snapshot(),
                prefs.getString("restartBeforePid", "")) == CompositorValues.Outcome.CONFIRMED)
            CompositorValues.recordCompiled(prefs, inputs, requested, status.get("sf_generation"));
    }

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
                        activity.getString(R.string.sf_executing, action.name()),
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
                                        activity.getString(R.string.sf_prepared);
                                break;
                            case RESTART:
                                Map<String, String> values = SfLive.normalizeValues(restartValues);
                                Map<String, String> freshStatus = flow.status();
                                io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.refresh();
                                if (!io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.available())
                                    throw new IOException(activity.getString(R.string.sf_props_unavailable));
                                Map<String, String> inputs = CompositorValues.restartInputs(
                                        io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.snapshot(), values);
                                android.content.SharedPreferences prefs = activity.getSharedPreferences(
                                        "sf_live_ui", Activity.MODE_PRIVATE);
                                // Committed before the restart kills this process.
                                CompositorValues.recordAttempt(prefs, inputs, System.currentTimeMillis(),
                                        freshStatus.getOrDefault("sf_generation", freshStatus.get("sf_pid")));
                                flow.applyAndRestart(values, written ->
                                        new io.github.igorcv88.oneuiliquidglass.config.ConfigRepository(activity)
                                                .adoptWritten(written));
                                message =
                                        activity.getString(R.string.sf_restart_started);
                                status = Collections.emptyMap();
                                break;
                            case LOG:
                                SfLiveFlow.Reply reply = run(bundle, SfLive.Command.LOG, null);
                                log = reply.output;
                                message = activity.getString(R.string.sf_recent_logs) + "\n" + log;
                                break;
                            case UNINSTALL:
                                SfLiveFlow.Reply removed =
                                        run(bundle, SfLive.Command.UNINSTALL, null);
                                if (removed.exit != 0
                                        || !"ok".equals(removed.values().get("uninstall")))
                                    throw new IOException(removed.output);
                                status = flow.status();
                                message =
                                        activity.getString(R.string.sf_removed);
                                break;
                            default:
                                status = flow.status();
                                message =
                                        activity.getString(R.string.sf_status_read);
                        }
                        io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.refresh();
                        if (action == Action.STATUS) confirmCompiled(status);
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
                                log = activity.getString(R.string.sf_log_failed, secondary.getMessage());
                            }
                        }
                        publish(
                                new Model(
                                        false,
                                        true,
                                        Collections.emptyMap(),
                                        activity.getString(R.string.sf_interrupted_prefix) + " "
                                                + localizedFailure(error)
                                                + "\n" + activity.getString(R.string.sf_no_further_restart)
                                                + (log.isEmpty() ? "" : "\n" + log),
                                        log, true));
                    }
                });
    }
}

package io.github.igorcv88.oneuiliquidglass.live;

import java.util.Map;

/**
 * Fixed protocol orchestration. Runs only on the caller's worker; restart is always a separate
 * call.
 */
public final class SfLiveFlow {
    public interface Runner {
        Reply run(SfLive.Command command, String mode) throws Exception;

        /** {@code setprops} with already validated compositor values (SfLive.setprops). */
        default Reply setprops(Map<String, String> values) throws Exception {
            throw new UnsupportedOperationException("setprops");
        }
    }

    public static final class Reply {
        public final int exit;
        public final String output;

        public Reply(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }

        public Map<String, String> values() {
            return SfLive.parse(output);
        }
    }

    public static final class Failure extends Exception {
        public final boolean selftest;

        Failure(String message, boolean selftest) {
            super(message);
            this.selftest = selftest;
        }
    }

    private final Runner runner;

    public SfLiveFlow(Runner runner) {
        this.runner = runner;
    }

    private Reply checked(SfLive.Command command, String mode) throws Exception {
        Reply reply = runner.run(command, mode);
        if (reply.exit != SfLive.OK)
            throw new Failure(
                    command + " recusado (" + reply.exit + ")\n" + reply.output,
                    command == SfLive.Command.SELFTEST);
        return reply;
    }

    public Map<String, String> status() throws Exception {
        Map<String, String> status = checked(SfLive.Command.STATUS, null).values();
        if (!"1".equals(status.get("helper")))
            throw new Failure("Helper sem resposta válida; root não confirmado.", false);
        return status;
    }

    private void supported(Map<String, String> status) throws Failure {
        if ("1".equals(status.get("legacy_conflict")))
            throw new Failure(
                    "O módulo legado oulg_sf ainda está ativo. Desative pelo método de root"
                        + " existente e faça reboot antes da rota live.",
                    false);
        if (!"1".equals(status.get("fingerprint_ok")))
            throw new Failure(
                    "Firmware fora da allowlist do bundle. Nada foi instalado/reiniciado.", false);
        if (!"1".equals(status.get("bundled_ok")))
            throw new Failure("Bundle inválido. Nada foi instalado/reiniciado.", false);
    }

    public Map<String, String> prepare() throws Exception {
        Map<String, String> status = status();
        supported(status);
        SfLive.State state = SfLive.state(status);
        if (state == SfLive.State.UPDATE) {
            Reply installed = checked(SfLive.Command.INSTALL, null);
            if (!"ok".equals(installed.values().get("install")))
                throw new Failure("Instalação não confirmada.\n" + installed.output, false);
            status = status();
            supported(status);
            if (!"0".equals(status.get("update_needed")) || !"1".equals(status.get("installed_ok")))
                throw new Failure("Instalação difere do bundle. Operação interrompida.", false);
            state = SfLive.State.SELFTEST;
        }
        if (state != SfLive.State.UPDATE && !"1".equals(status.get("installed_ok")))
            throw new Failure("Instalação inconsistente; prepare novamente.", false);
        if (state == SfLive.State.SELFTEST) {
            Reply tested = checked(SfLive.Command.SELFTEST, null);
            if (!"ok".equals(tested.values().get("selftest"))
                    || !"0".equals(tested.values().get("rc")))
                throw new Failure("Selftest não aprovado.\n" + tested.output, true);
            status = status();
            supported(status);
            if (!"1".equals(status.get("selftest_ok")))
                throw new Failure(
                        "Stamp do selftest não corresponde aos binários instalados.", true);
        }
        return status;
    }

    /**
     * Writes the desired compositor values, reads each back, and only then restarts: a value
     * the helper did not confirm stops the operation before anything restarts. The restart
     * compiles the shaders with these values; the caller still has to observe the result.
     */
    public Reply applyAndRestart(Map<String, String> values) throws Exception {
        return applyAndRestart(values, null);
    }

    /** {@code confirmed} runs after every value read back and before the restart. */
    public Reply applyAndRestart(Map<String, String> values, java.util.function.Consumer<Map<String, String>> confirmed)
            throws Exception {
        if (!values.isEmpty()) {
            Map<String, String> status = status();
            supported(status);
            ready(status);
            Reply set = runner.setprops(values);
            Map<String, String> echo = set.values();
            if (set.exit != SfLive.OK || !"ok".equals(echo.get("setprops")))
                throw new Failure("Valores recusados; nada foi reiniciado.\n" + set.output, false);
            for (Map.Entry<String, String> e : values.entrySet()) {
                String name = "set_" + e.getKey().substring(3);
                if (!e.getValue().equals(echo.get(name)))
                    throw new Failure(
                            "O valor de " + e.getKey() + " não foi confirmado; nada foi reiniciado.",
                            false);
            }
            if (confirmed != null) confirmed.accept(values);
        }
        return restartConfirmed();
    }

    private static void ready(Map<String, String> status) throws Failure {
        if (!"0".equals(status.get("update_needed"))
                || !"1".equals(status.get("installed_ok"))
                || !"1".equals(status.get("selftest_ok")))
            throw new Failure(
                    "Binários mudaram ou selftest ausente. Prepare novamente antes de reiniciar.",
                    false);
    }

    public Reply restartConfirmed() throws Exception {
        // Revalidate after confirmation: UI state may have become stale while the dialog was open.
        Map<String, String> status = status();
        supported(status);
        ready(status);
        Reply reply = checked(SfLive.Command.RESTART, "rewrite");
        if (!"started".equals(reply.values().get("restart")))
            throw new Failure("Início do reinício não confirmado.\n" + reply.output, false);
        return reply; // A detached daemon starting is not evidence of a loaded library or rewrite.
    }
}

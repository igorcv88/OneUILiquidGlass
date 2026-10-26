package io.github.igorcv88.oneuiliquidglass.live;

import android.app.Activity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Two fixed app actions. No interpolated shell input and no root prompt at launch. */
public final class RootAccess {
    private final Activity activity;
    private final ExecutorService worker;
    private boolean granted, busy, closed;
    private String message = "Root ainda não verificado";
    private Runnable listener = () -> {};

    public RootAccess(Activity activity, ExecutorService worker) {
        this.activity = activity;
        this.worker = worker;
    }

    public boolean granted() {
        return granted && !busy;
    }

    public boolean busy() {
        return busy;
    }

    public String message() {
        return message;
    }

    public void listen(Runnable listener) {
        this.listener = listener;
        listener.run();
    }

    public void close() {
        closed = true;
        listener = () -> {};
    }

    public void check(Consumer<Boolean> done) {
        execute(false, done);
    }

    public void restartSystemUi(Consumer<Boolean> done) {
        if (granted()) execute(true, done);
    }

    private static String run(String fixed) throws Exception {
        Process p = new ProcessBuilder("su", "-c", fixed).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> output =
                reader.submit(
                        () ->
                                new String(
                                        p.getInputStream().readNBytes(8192),
                                        StandardCharsets.UTF_8));
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Autorização SU expirou");
            String result = output.get(2, TimeUnit.SECONDS).trim();
            if (p.exitValue() != 0) throw new IOException("SU recusado ou indisponível");
            return result;
        } finally {
            p.destroy();
            reader.shutdownNow();
        }
    }

    private void execute(boolean restart, Consumer<Boolean> done) {
        if (closed || busy) return;
        busy = true;
        message = restart ? "Reiniciando SystemUI…" : "Autorize o acesso SU para este aplicativo…";
        listener.run();
        worker.execute(
                () -> {
                    boolean ok = false;
                    String result;
                    try {
                        if (!"0".equals(run("/system/bin/id -u")))
                            throw new IOException("SU não retornou UID 0");
                        if (restart) run("/system/bin/killall com.android.systemui");
                        ok = true;
                        result =
                                restart
                                        ? "Reinício solicitado. Aguardando nova leitura da"
                                              + " SystemUI."
                                        : "Acesso root confirmado";
                    } catch (Exception e) {
                        result = e.getMessage() == null ? "Root indisponível" : e.getMessage();
                    }
                    final boolean success = ok;
                    final String status = result;
                    activity.runOnUiThread(
                            () -> {
                                if (closed || activity.isFinishing() || activity.isDestroyed())
                                    return;
                                granted = success;
                                busy = false;
                                message = status;
                                listener.run();
                                done.accept(success);
                            });
                });
    }
}

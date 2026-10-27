package io.github.igorcv88.oneuiliquidglass.live;

import android.app.Activity;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Three fixed app actions. No interpolated shell input and no root prompt at launch. */
public final class RootAccess {
    private enum Action { CHECK, SYSTEM_UI, SOFT_REBOOT }

    /**
     * "Soft reboot": compositor and zygote restart with the shader caches cleared. Detached with
     * nohup because the app itself dies with zygote. A compositor restart drops a lens injected at
     * runtime, so the refraction flag is cleared unless the running compositor has the boot
     * module's overlay mounted (checked before the stop, as oulg-live.sh does): that overlay is
     * what loads the lens again. Module markers describe the next boot, not the current one.
     */
    static final String SOFT_REBOOT =
            "nohup sh -c 'p=$(pidof surfaceflinger | cut -d \" \" -f 1); keep=0;"
                    + " [ -n \"$p\" ] && grep -Fq /oulg_sf/ /proc/$p/mountinfo && keep=1;"
                    + " stop surfaceflinger;"
                    + " rm -f /data/misc/surfaceflinger/skia_shaders /data/misc/surfaceflinger/egl_shaders"
                    + " /data/misc/surfaceflinger/oulg_shaders.txt;"
                    + " [ \"$keep\" = 1 ] || setprop debug.oulg.sfrefract \"\";"
                    + " start surfaceflinger; sleep 3; setprop ctl.restart zygote' >/dev/null 2>&1 &";

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
        execute(Action.CHECK, done);
    }

    public void restartSystemUi(Consumer<Boolean> done) {
        if (granted()) execute(Action.SYSTEM_UI, done);
    }

    /** Restarts the compositor and zygote; every app closes, this one included. */
    public void softReboot(Consumer<Boolean> done) {
        if (granted()) execute(Action.SOFT_REBOOT, done);
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

    private void execute(Action action, Consumer<Boolean> done) {
        if (closed || busy) return;
        busy = true;
        message =
                action == Action.SYSTEM_UI
                        ? "Reiniciando SystemUI…"
                        : action == Action.SOFT_REBOOT
                                ? "Reiniciando userspace…"
                                : "Autorize o acesso SU para este aplicativo…";
        listener.run();
        worker.execute(
                () -> {
                    boolean ok = false;
                    String result;
                    try {
                        if (!"0".equals(run("/system/bin/id -u")))
                            throw new IOException("SU não retornou UID 0");
                        if (action == Action.SYSTEM_UI)
                            run("/system/bin/killall com.android.systemui");
                        else if (action == Action.SOFT_REBOOT) run(SOFT_REBOOT);
                        ok = true;
                        result =
                                action == Action.SYSTEM_UI
                                        ? "Reinício solicitado. Aguardando nova leitura da"
                                              + " SystemUI."
                                        : action == Action.SOFT_REBOOT
                                                ? "Reinício do userspace solicitado"
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

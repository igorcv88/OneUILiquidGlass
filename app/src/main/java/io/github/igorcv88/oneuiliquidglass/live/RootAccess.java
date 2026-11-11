package io.github.igorcv88.oneuiliquidglass.live;

import android.app.Activity;
import io.github.igorcv88.oneuiliquidglass.R;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Three fixed app actions. No interpolated shell input and no root prompt at launch. */
public final class RootAccess {
    private enum Action { CHECK, SYSTEM_UI, SOFT_REBOOT }

    /**
     * Plain userspace restart, used only when a fresh read says the dynamic lens is disabled.
     * A running lens must instead use SfLiveController's verified reinjection path. This fallback
     * clears the refraction flag rather than leaving SystemUI tagging cards without a shader.
     */
    static final String SOFT_REBOOT =
            "nohup sh -c 'stop surfaceflinger;"
                    + " rm -f /data/misc/surfaceflinger/skia_shaders /data/misc/surfaceflinger/egl_shaders"
                    + " /data/misc/surfaceflinger/oulg_shaders.txt;"
                    + " setprop debug.oulg.sfrefract \"\";"
                    + " start surfaceflinger; sleep 3; setprop ctl.restart zygote' >/dev/null 2>&1 &";

    private final Activity activity;
    private final ExecutorService worker;
    private boolean granted, busy, closed;
    private int messageId;
    private String detail;
    private static final class RootFailure extends IOException {
        final int messageId;
        RootFailure(int messageId) { this.messageId = messageId; }
    }
    private Runnable listener = () -> {};

    public RootAccess(Activity activity, ExecutorService worker) {
        this.activity = activity;
        this.worker = worker;
        messageId = R.string.root_unchecked;
    }

    public boolean granted() {
        return granted && !busy;
    }

    public boolean busy() {
        return busy;
    }

    public String message() {
        return messageId != 0 ? activity.getString(messageId) : detail;
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

    private String run(String fixed) throws Exception {
        Process p = new ProcessBuilder("su", "-c", fixed).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> output =
                reader.submit(
                        () ->
                                new String(
                                        p.getInputStream().readNBytes(8192),
                                        StandardCharsets.UTF_8));
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) throw new RootFailure(R.string.root_timeout);
            String result = output.get(2, TimeUnit.SECONDS).trim();
            if (p.exitValue() != 0) throw new RootFailure(R.string.root_denied_unavailable);
            return result;
        } finally {
            p.destroy();
            reader.shutdownNow();
        }
    }

    private void execute(Action action, Consumer<Boolean> done) {
        if (closed || busy) return;
        busy = true;
        messageId = action == Action.SYSTEM_UI ? R.string.root_restarting_ui
                : action == Action.SOFT_REBOOT ? R.string.root_restarting_userspace : R.string.root_authorize;
        listener.run();
        worker.execute(
                () -> {
                    boolean ok = false;
                    int resultId;
                    String resultDetail = null;
                    try {
                        if (!"0".equals(run("/system/bin/id -u")))
                            throw new RootFailure(R.string.root_bad_uid);
                        if (action == Action.SYSTEM_UI)
                            run("/system/bin/killall com.android.systemui");
                        else if (action == Action.SOFT_REBOOT) run(SOFT_REBOOT);
                        ok = true;
                        resultId = action == Action.SYSTEM_UI ? R.string.root_ui_requested
                                : action == Action.SOFT_REBOOT ? R.string.root_userspace_requested : R.string.root_confirmed;
                    } catch (Exception e) {
                        resultId = e instanceof RootFailure ? ((RootFailure) e).messageId
                                : e.getMessage() == null ? R.string.root_unavailable : 0;
                        resultDetail = e.getMessage();
                    }
                    final boolean success = ok;
                    final int statusId = resultId;
                    final String statusDetail = resultDetail;
                    activity.runOnUiThread(
                            () -> {
                                if (closed || activity.isFinishing() || activity.isDestroyed())
                                    return;
                                granted = success;
                                busy = false;
                                messageId = statusId;
                                detail = statusDetail;
                                listener.run();
                                done.accept(success);
                            });
                });
    }
}

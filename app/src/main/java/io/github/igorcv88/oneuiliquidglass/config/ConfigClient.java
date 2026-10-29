package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

import java.util.*;

/** One worker per SystemUI process. Binder and provider startup never occur from onDraw. */
public final class ConfigClient {
    private static volatile Map<String, String> current = Collections.emptyMap();
    private static volatile long revision;
    private static Handler worker;
    private static int failedReads;

    public static String override(String key) {
        return current.get(key);
    }

    public static boolean flag(String key) {
        String value = current.get(key);
        return value == null ? ProductFlags.defaultEnabled(key) : !"0".equals(value);
    }

    public static long revision() {
        return revision;
    }

    public static synchronized void start(
            Context context, java.util.function.Consumer<Boolean> onEnabled) {
        if (worker != null) return;
        Context app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("oulg-config");
        thread.start();
        worker = new Handler(thread.getLooper());
        Handler main = new Handler(Looper.getMainLooper());
        boolean[] observerTriggered = {false}; // Accessed only by the worker handler.
        Runnable refresh =
                new Runnable() {
                    @Override
                    public void run() {
                        boolean observed = observerTriggered[0];
                        observerTriggered[0] = false;
                        try (Cursor c =
                                app.getContentResolver()
                                        .query(ConfigRepository.SNAPSHOT, null, null, null, null)) {
                            if (c != null) {
                                Map<String, String> next = new LinkedHashMap<>();
                                while (c.moveToNext()) {
                                    String key = c.getString(0), value = c.getString(1);
                                    SettingSpec spec = SettingsRegistry.get(key);
                                    if (spec != null
                                            && spec.applyMode == SettingSpec.ApplyMode.LIVE
                                            && !spec.helperOwned())
                                        next.put(key, spec.validate(value));
                                    else if (ProductFlags.known(key)
                                            && (value.equals("1") || value.equals("0")))
                                        next.put(key, value);
                                    else if (key.equals("enabled")
                                            || key.equals("schemaVersion")
                                            || key.equals("generation")) next.put(key, value);
                                }
                                if (!"1".equals(next.get("schemaVersion")))
                                    throw new IllegalArgumentException("schema");
                                boolean changed = !next.equals(current);
                                if (changed) {
                                    current = Collections.unmodifiableMap(next);
                                    revision++;
                                    final long published = revision;
                                    final boolean on = "1".equals(next.get("enabled"));
                                    Probe.log("CONFIG_RECEIVED", "revision=" + published
                                            + " keys=" + next.size() + " enabled=" + on
                                            + " source=" + (observed ? "observer" : "poll"));
                                    main.post(
                                            () -> {
                                                try {
                                                    onEnabled.accept(on);
                                                    // Callback completed; not proof that a visible
                                                    // Compose/notification node has been repainted.
                                                    Probe.log("CONFIG_DISPATCH", "revision=" + published
                                                            + " delivered=true");
                                                } catch (RuntimeException | LinkageError e) {
                                                    Probe.error("CONFIG_DISPATCH_FAILED", e);
                                                }
                                            });
                                }
                                // Acknowledges the read, not optical fidelity, surface eligibility
                                // or shader execution.
                                android.content.Intent ack =
                                        new android.content.Intent(
                                                        "io.github.igorcv88.oneuiliquidglass.CONFIG_READ")
                                                .setComponent(
                                                        new android.content.ComponentName(
                                                                io.github.igorcv88.oneuiliquidglass
                                                                        .Config.PACKAGE,
                                                                io.github.igorcv88.oneuiliquidglass
                                                                                .Config.PACKAGE
                                                                        + ".ConfigReadReceiver"))
                                                .putExtra("generation", next.get("generation"));
                                app.sendBroadcast(ack);
                                if (observed) Probe.log("CONFIG_OBSERVER_READ", "changed=" + changed
                                        + " revision=" + revision);
                            }
                        } catch (RuntimeException e) {
                            // Keep the snapshot; the first few failures should be diagnosable
                            // instead of silently looking like a delayed/impossible live setting.
                            if (failedReads++ < 3) Probe.error("CONFIG_READ_FAILED", e);
                        }
                        worker.removeCallbacks(this);
                        worker.postDelayed(this, 10000);
                    }
                };
        worker.post(
                () -> {
                    try {
                        app.getContentResolver()
                                .registerContentObserver(
                                        ConfigRepository.SNAPSHOT,
                                        false,
                                        new ContentObserver(worker) {
                                            @Override
                                            public void onChange(boolean self) {
                                                observerTriggered[0] = true;
                                                worker.removeCallbacks(refresh);
                                                worker.post(refresh);
                                            }
                                        });
                        Probe.log("CONFIG_OBSERVER_READY", "registered=true fallbackPollMs=10000");
                    } catch (RuntimeException e) {
                        Probe.error("CONFIG_OBSERVER_FAILED", e);
                        // 10s polling remains.
                    }
                    refresh.run();
                });
    }

    private ConfigClient() {}
}

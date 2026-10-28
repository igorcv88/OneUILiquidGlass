package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.*;

/** One worker per SystemUI process. Binder and provider startup never occur from onDraw. */
public final class ConfigClient {
    private static volatile Map<String, String> current = Collections.emptyMap();
    private static volatile long revision;
    private static Handler worker;

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
        Runnable refresh =
                new Runnable() {
                    @Override
                    public void run() {
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
                                if (!next.equals(current)) {
                                    current = Collections.unmodifiableMap(next);
                                    revision++;
                                    main.post(
                                            () ->
                                                    onEnabled.accept(
                                                            "1".equals(next.get("enabled"))));
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
                            }
                        } catch (RuntimeException ignored) {
                            /* Keep last good snapshot; empty initial snapshot falls back to props. */
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
                                                worker.removeCallbacks(refresh);
                                                worker.post(refresh);
                                            }
                                        });
                    } catch (RuntimeException ignored) {
                        /* 10s polling remains. */
                    }
                    refresh.run();
                });
    }

    private ConfigClient() {}
}

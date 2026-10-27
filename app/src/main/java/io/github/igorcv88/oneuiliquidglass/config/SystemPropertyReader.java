package io.github.igorcv88.oneuiliquidglass.config;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One unprivileged getprop snapshot, read off the UI thread. No hidden-API exemption or root. */
public final class SystemPropertyReader {
    private static volatile Map<String, String> observed = Collections.emptyMap();
    private static volatile boolean available;

    public static boolean available() {
        return available;
    }

    /** Every observed debug.oulg.* value from the last successful read. */
    public static Map<String, String> snapshot() {
        return observed;
    }

    public static String read(String key) {
        return SettingsRegistry.get(key) == null ? "" : observed.getOrDefault(key, "");
    }

    public static void refresh() {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start();
            final Process running = process;
            Future<Map<String, String>> output =
                    reader.submit(
                            () -> {
                                Map<String, String> next = new LinkedHashMap<>();
                                try (BufferedReader lines =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        running.getInputStream(),
                                                        java.nio.charset.StandardCharsets.UTF_8))) {
                                    String line;
                                    int count = 0;
                                    while ((line = lines.readLine()) != null) {
                                        if (++count > 8192)
                                            throw new IOException("getprop output too large");
                                        if (!line.startsWith("[debug.oulg.")) continue;
                                        int separator = line.indexOf("]: [");
                                        if (separator < 0 || !line.endsWith("]")) continue;
                                        String key = line.substring(12, separator),
                                                value =
                                                        line.substring(
                                                                separator + 4, line.length() - 1);
                                        if (SettingsRegistry.get(key) != null
                                                && value.length() <= 96) next.put(key, value);
                                    }
                                }
                                return next;
                            });
            Map<String, String> next = output.get(4, TimeUnit.SECONDS);
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("getprop failed");
            observed = Collections.unmodifiableMap(next);
            available = true;
        } catch (Exception e) {
            available = false; /* Keep last snapshot, report that a fresh read failed. */
        } finally {
            if (process != null) process.destroyForcibly();
            reader.shutdownNow();
        }
    }

    private SystemPropertyReader() {}
}

package io.github.igorcv88.oneuiliquidglass.live;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SurfaceFlinger live bundle shipped in this APK (assets/sfhook/, built by sfhook/build.sh) and
 * the commands its root helper accepts. The app only extracts the bundle and runs
 * {@code su -c "sh <bundle>/oulg-live.sh <command>"}; everything privileged happens in that helper,
 * which takes a fixed set of commands and answers in key=value lines (docs/sf-live-app.md).
 *
 * <p>Nothing here runs a process: the caller owns threading, the su prompt and the UI.</p>
 */
public final class SfLive {
    private SfLive() {}

    /** Asset directory of the bundle, and the files in it. */
    public static final String ASSET_DIR = "sfhook";
    static final List<String> FILES = Collections.unmodifiableList(Arrays.asList(
            "oulg_inject", "liboulg_sf.so", "oulg-live.sh", "allowed_fingerprints", "live.prop"));
    public static final String HELPER = "oulg-live.sh", MANIFEST = "live.prop";

    /** Helper exit codes. */
    public static final int OK = 0, FAILED = 1, REFUSED = 2;

    /** Whether this APK carries the bundle (a local build without the NDK step does not). */
    public static boolean bundled(Context context) {
        try {
            String[] names = context.getAssets().list(ASSET_DIR);
            return names != null && Arrays.asList(names).containsAll(FILES);
        } catch (IOException e) { return false; }
    }

    /** The bundle's manifest (version, versionCode, commit, sha256_*), or an empty map. */
    public static Map<String, String> manifest(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + MANIFEST)) {
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) { return new LinkedHashMap<>(); }
    }

    /**
     * Copies the bundle to {@code <filesDir>/sfhook} and returns that directory. Root reads it from
     * there; the helper checks the files against the manifest before installing anything.
     */
    public static File extract(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), ASSET_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        AssetManager assets = context.getAssets();
        for (String name : FILES) {
            File out = new File(dir, name), tmp = new File(dir, name + ".tmp");
            try (InputStream in = assets.open(ASSET_DIR + "/" + name); OutputStream o = new FileOutputStream(tmp)) {
                in.transferTo(o);
            }
            if (!tmp.renameTo(out)) throw new IOException("rename " + out);
        }
        // Read and executed by root only; the app keeps them private.
        new File(dir, "oulg_inject").setExecutable(true, true);
        return dir;
    }

    /** The helper's commands. RESTART takes a mode: "rewrite" (the lens) or "dump" (shaders only). */
    public enum Command {
        STATUS("status"), INSTALL("install"), SELFTEST("selftest"), RESTART("restart"), SETPROPS("setprops"), LOG("log"), UNINSTALL("uninstall");
        final String word;
        Command(String word) { this.word = word; }
    }

    /**
     * The argv for running one helper command as root: {@code su -c "sh '<dir>/oulg-live.sh' cmd"}.
     * Only the fixed commands and, for RESTART, the two modes are accepted; nothing the user typed
     * reaches the shell. RESTART restarts the whole userspace (the app included).
     */
    public static List<String> command(String bundleDir, Command command, String mode) {
        if (bundleDir == null || !bundleDir.startsWith("/") || bundleDir.contains("'")) throw new IllegalArgumentException("bundleDir");
        String line = "sh '" + bundleDir + "/" + HELPER + "' " + command.word;
        if (command == Command.RESTART) {
            if (!"rewrite".equals(mode) && !"dump".equals(mode)) throw new IllegalArgumentException("mode");
            line += " " + mode;
        } else if (mode != null || command == Command.SETPROPS) throw new IllegalArgumentException("mode");
        return Arrays.asList("su", "-c", line);
    }

    /**
     * {@code setprops name=value...} for compositor parameters. Names are the library's short
     * names (sf.kgcore -> kgcore) and must be in {@link #WRITABLE}; values are plain decimals or
     * empty (back to the library default). The helper checks both again before writing.
     */
    public static List<String> setprops(String bundleDir, Map<String, String> values) {
        List<String> base = command(bundleDir, Command.STATUS, null);
        if (values.isEmpty()) throw new IllegalArgumentException("empty");
        StringBuilder line = new StringBuilder(base.get(2).replaceFirst(" status$", " setprops"));
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey(), value = e.getValue();
            if (!key.startsWith("sf.") || !WRITABLE.contains(key.substring(3)))
                throw new IllegalArgumentException("key " + key);
            if (!value.matches("(-?[0-9]+(\\.[0-9]+)?)?") || value.length() > 12)
                throw new IllegalArgumentException("value " + key);
            line.append(' ').append(key.substring(3)).append('=').append(value);
        }
        return Arrays.asList("su", "-c", line.toString());
    }

    /** Mirrors the helper's allowlist: the library's reader minus the version marker and the
     * rewrite switch the restart command owns. */
    public static final List<String> WRITABLE = Collections.unmodifiableList(Arrays.asList(
            "core", "taps", "ramp", "hubloom", "edgeblur", "kgcore", "kgtaps", "kgramp", "kgsat", "kgtone",
            "kgrim", "kgspec", "kgflat", "kglevel", "kgcool", "kgtilt", "kgline", "kgcorecap",
            "kggradient", "kgband", "kgbodylens", "kgblurexp", "kgflatcap", "kgcoredim", "kgglow", "shadecap", "shadelift",
            "hulight", "kglight", "shadelight", "qslens", "qsbody", "qsdepth", "debug"));

    /** key=value lines (the helper's output, a live.prop); comments and other lines are skipped. */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
            out.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return out;
    }

    /** What the "status" answer means for the control panel's button. */
    public enum State {
        /** Root helper did not answer (no su, refused prompt). */
        NO_ROOT,
        /** This firmware is not on the validated list: nothing is installed or restarted. */
        UNSUPPORTED,
        /** The bundled binaries differ from the installed ones (or none are installed): install, self-test, restart. */
        UPDATE,
        /** Installed and current, but no passing self-test of them is on record: self-test, then restart. */
        SELFTEST,
        /** Installed, current and self-tested, but surfaceflinger has not loaded them (after a reboot): restart. */
        RESTART,
        /** surfaceflinger runs with the current library: only the APK needed updating, nothing to do. */
        LOADED
    }

    public static State state(Map<String, String> status) {
        if (!"1".equals(status.get("helper"))) return State.NO_ROOT;
        if (!"1".equals(status.get("fingerprint_ok"))) return State.UNSUPPORTED;
        if (!"0".equals(status.get("update_needed"))) return State.UPDATE;
        if (!"1".equals(status.get("selftest_ok"))) return State.SELFTEST;
        if (!"1".equals(status.get("sf_loaded"))) return State.RESTART;
        return State.LOADED;
    }
}

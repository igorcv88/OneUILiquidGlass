package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Locale;

/**
 * R5 4.4: opt-in, selective, time-limited isolation of the optical layers of ONE Quick Settings
 * glass box, to tell which layer draws the flattened corner light or the outer halo of the
 * collective panel. Off by default; nothing else changes.
 *
 * <p>{@code setprop debug.oulg.qslayers <id>:<mask>}, read once when the panel opens (never per
 * frame). {@code id} is the token id QS_EDGE_GEOMETRY / QS_OPTICS_DRAWN print for the target box
 * (its bound background node: stable while that node lives). Mask bits, one layer per bit:
 * 1 = SurfaceFlinger profile 3 (the region is sent with an integer radius, so the compositor
 * keeps a plain blur region without the QS light/refraction tag); 2 = the AGSL optics of that
 * box; 4 = its Compose outline (transparent brush). Other boxes and surfaces keep their
 * material. The isolation ends by itself {@link #WINDOW_MS} after the panel opened, at the next
 * draw; {@code setprop debug.oulg.qslayers ""} disarms it on the next opening.</p>
 */
final class QsLayerIsolation {
    static final String PROP = "debug.oulg.qslayers";
    static final int SF_PROFILE3 = 1, AGSL = 2, COMPOSE_RIM = 4;
    static final long WINDOW_MS = 120_000L;

    private volatile long target = -1;
    private volatile int mask;
    private volatile long armedAt;
    /** The target's box (BoxWithOutline restart scope), learned from its bound token. */
    private volatile java.lang.ref.WeakReference<Object> box = new java.lang.ref.WeakReference<>(null);

    /** "id:mask" with id >= 1 and mask in 1..7; anything else disarms. */
    static long[] parse(String value) {
        if (value == null) return null;
        String v = value.trim();
        int colon = v.indexOf(':');
        if (colon <= 0 || colon == v.length() - 1) return null;
        try {
            long id = Long.parseLong(v.substring(0, colon).trim());
            int m = Integer.parseInt(v.substring(colon + 1).trim());
            if (id < 1 || m < 1 || m > 7) return null;
            return new long[] {id, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Panel opened: (re)arm from the property value; returns the log line, or null if off. */
    String arm(String value, long now) {
        long[] p = parse(value);
        if (p == null) { target = -1; mask = 0; box = new java.lang.ref.WeakReference<>(null); return null; }
        if (p[0] != target) box = new java.lang.ref.WeakReference<>(null);
        target = p[0];
        mask = (int) p[1];
        armedAt = now;
        return String.format(Locale.ROOT, "target=%d mask=%d sfProfile3=%s agsl=%s composeRim=%s windowMs=%d",
                target, mask, on(SF_PROFILE3) ? "off" : "on", on(AGSL) ? "off" : "on",
                on(COMPOSE_RIM) ? "off" : "on", WINDOW_MS);
    }

    private boolean on(int layer) { return (mask & layer) != 0; }

    boolean active(long now) { return target > 0 && mask != 0 && now - armedAt >= 0 && now - armedAt < WINDOW_MS; }

    /** Whether {@code layer} of the box drawn by token {@code id} is removed now. */
    boolean off(long id, int layer, long now) {
        return id == target && on(layer) && active(now);
    }

    /** The target token's box, so its outline (built by another token) can be found. */
    void learn(long id, Object scope) {
        if (id == target && scope != null && box.get() != scope) box = new java.lang.ref.WeakReference<>(scope);
    }

    /** Whether the outline of this box is removed now. */
    boolean rimOff(Object scope, long now) {
        return scope != null && scope == box.get() && on(COMPOSE_RIM) && active(now);
    }

    long target() { return target; }

    private final java.util.Set<Integer> reported = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** True the first time a layer is actually removed after arming: one log line per layer. */
    boolean firstHit(int layer) { return reported.add(layer); }

    void resetHits() { reported.clear(); }

    /** The property, read with the platform getter; empty when unreadable. */
    static String read() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object v = props.getMethod("get", String.class, String.class).invoke(null, PROP, "");
            return v == null ? "" : String.valueOf(v);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "";
        }
    }
}

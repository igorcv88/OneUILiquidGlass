package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * R5 4.4: opt-in, selective, time-limited isolation of the optical layers of Quick Settings glass
 * boxes, to tell which layer draws a contour. Off by default; nothing else changes.
 *
 * <p>{@code setprop debug.oulg.qslayers <selector>:<mask>}, read once when the panel opens (never
 * per frame). The selector names surfaces by what persists across sessions, not by a token id
 * alone (token ids change with every SystemUI session and recomposition; the first device run
 * targeted 710, the landscape panel of an earlier session, and nothing matched):</p>
 * <ul>
 *   <li>{@code 1336x909}: inner box size in px as QS_OPTICS_DRAWN prints it (w x h, +-3 px);</li>
 *   <li>{@code container}, {@code slider}, {@code media-active}, {@code media-empty}: a role;</li>
 *   <li>{@code container@652x282}: a role and a size;</li>
 *   <li>{@code 2145}: a token id (one session only).</li>
 * </ul>
 * <p>Mask bits, one layer per bit: 1 = SurfaceFlinger profile 3 (the region is sent with an
 * integer radius: same region and blur, without the QS light/refraction tag); 2 = the AGSL optics;
 * 4 = the Compose outline (transparent brush). Every matching box is affected, nothing else. The
 * isolation ends by itself {@link #WINDOW_MS} after the panel opened; {@code setprop
 * debug.oulg.qslayers ""} disarms it on the next opening.</p>
 */
final class QsLayerIsolation {
    static final String PROP = "debug.oulg.qslayers";
    static final int SF_PROFILE3 = 1, AGSL = 2, COMPOSE_RIM = 4;
    static final long WINDOW_MS = 120_000L;
    static final float SIZE_TOLERANCE = 3f;

    /** A parsed selector; any field may be absent (-1 / null). */
    static final class Selector {
        final long id;
        final String role;
        final float w, h;
        final int mask;
        Selector(long id, String role, float w, float h, int mask) {
            this.id = id; this.role = role; this.w = w; this.h = h; this.mask = mask;
        }
        boolean matches(long tokenId, String tokenRole, float bw, float bh) {
            if (id > 0 && tokenId != id) return false;
            if (role != null && !role.equals(tokenRole)) return false;
            if (w > 0 && (!(Math.abs(bw - w) <= SIZE_TOLERANCE) || !(Math.abs(bh - h) <= SIZE_TOLERANCE))) return false;
            return true;
        }
        @Override public String toString() {
            return (id > 0 ? "id=" + id + " " : "") + (role != null ? "role=" + role + " " : "")
                    + (w > 0 ? String.format(Locale.ROOT, "size=%.0fx%.0f ", w, h) : "") + "mask=" + mask;
        }
    }

    private static final Set<String> ROLES = new HashSet<>(java.util.Arrays.asList(
            "container", "slider", "media-active", "media-empty"));

    /** "selector:mask"; null for anything malformed (disarms). */
    static Selector parse(String value) {
        if (value == null) return null;
        String v = value.trim();
        int colon = v.lastIndexOf(':');
        if (colon <= 0 || colon == v.length() - 1) return null;
        int mask;
        try { mask = Integer.parseInt(v.substring(colon + 1).trim()); }
        catch (NumberFormatException e) { return null; }
        if (mask < 1 || mask > 7) return null;
        String sel = v.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String role = null, size = null;
        int at = sel.indexOf('@');
        if (at >= 0) { role = sel.substring(0, at); size = sel.substring(at + 1); }
        else if (ROLES.contains(sel)) role = sel;
        else if (sel.indexOf('x') > 0) size = sel;
        else {
            try {
                long id = Long.parseLong(sel);
                return id < 1 ? null : new Selector(id, null, -1, -1, mask);
            } catch (NumberFormatException e) { return null; }
        }
        if (role != null && !ROLES.contains(role)) return null;
        float w = -1, h = -1;
        if (size != null) {
            int x = size.indexOf('x');
            if (x <= 0 || x == size.length() - 1) return null;
            try { w = Float.parseFloat(size.substring(0, x)); h = Float.parseFloat(size.substring(x + 1)); }
            catch (NumberFormatException e) { return null; }
            if (!(w > 0) || !(h > 0)) return null;
        }
        return new Selector(-1, role, w, h, mask);
    }

    private volatile Selector selector;
    private volatile long armedAt;
    /** Boxes (BoxWithOutline scopes) of matching surfaces, and whether each still awaits a recompose. */
    private final Map<Object, Boolean> boxes = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Integer> reported = Collections.synchronizedSet(new HashSet<>());

    /** Panel opened: (re)arm from the property value; returns the log line, or null if off. */
    String arm(String value, long now) {
        Selector s = parse(value);
        selector = s;
        boxes.clear();
        reported.clear();
        if (s == null) return null;
        armedAt = now;
        return String.format(Locale.ROOT, "%s sfProfile3=%s agsl=%s composeRim=%s windowMs=%d", s,
                on(s, SF_PROFILE3) ? "off" : "on", on(s, AGSL) ? "off" : "on",
                on(s, COMPOSE_RIM) ? "off" : "on", WINDOW_MS);
    }

    private static boolean on(Selector s, int layer) { return s != null && (s.mask & layer) != 0; }

    boolean active(long now) {
        return selector != null && now - armedAt >= 0 && now - armedAt < WINDOW_MS;
    }

    /** Whether this box matches the armed selector now (any layer). */
    boolean matches(long id, String role, float w, float h, long now) {
        Selector s = selector;
        return s != null && active(now) && s.matches(id, role, w, h);
    }

    /** Whether {@code layer} of this box is removed now. */
    boolean off(long id, String role, float w, float h, int layer, long now) {
        return on(selector, layer) && matches(id, role, w, h, now);
    }

    /** A matching box's scope, so its outline (built by another token) can be found. */
    /** Returns true when the box is new and its outline is to be removed: recompose it once. */
    boolean learn(Object scope) {
        if (scope == null || !on(selector, COMPOSE_RIM)) return false;
        synchronized (boxes) {
            if (boxes.containsKey(scope)) return false;
            boxes.put(scope, Boolean.TRUE);
            return true;
        }
    }

    /** Whether the outline of this box is removed now. */
    boolean rimOff(Object scope, long now) {
        return scope != null && on(selector, COMPOSE_RIM) && active(now) && boxes.containsKey(scope);
    }

    String selector() { Selector s = selector; return s == null ? "none" : s.toString(); }

    /** True the first time a layer is actually removed after arming: one log line per layer. */
    boolean firstHit(int layer) { return reported.add(layer); }

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

package io.github.igorcv88.oneuiliquidglass.hooks;

/** A row which is repeatedly rebound must stop replacing drawables to protect SystemUI. */
final class IconRebindGuard {
    static final long WINDOW_MS = 700;
    static final int MAX_REBINDS = 8;
    private long windowStart = -1;
    private int count;
    private boolean tripped;
    private String notificationKey;

    /** A recycled notification row may host a different SBN after the original circuit trip. */
    boolean bindNotification(String key) {
        if (key == null || key.equals(notificationKey)) return false;
        reset();
        notificationKey = key;
        return true;
    }

    boolean record(long now, int rebindings) {
        if (tripped) return true;
        if (rebindings <= 0) return false;
        if (windowStart < 0 || now - windowStart > WINDOW_MS) {
            windowStart = now;
            count = 0;
        }
        count += rebindings;
        return tripped = count > MAX_REBINDS;
    }

    boolean tripped() { return tripped; }
    int count() { return count; }

    void reset() {
        windowStart = -1;
        count = 0;
        tripped = false;
        notificationKey = null;
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

/** Pure decisions for rate-limited, draw-time notification-background diagnostics. */
final class ShadeLayerTrace {
    private ShadeLayerTrace() {}

    static String role(Object background, Object normal, Object dimmed) {
        if (background == null) return "unknown";
        if (background == normal && background == dimmed) return "both";
        if (background == normal) return "normal";
        if (background == dimmed) return "dimmed";
        return "other";
    }

    static boolean emit(int drawCount, String current, String previous, long now, long lastAt) {
        return drawCount <= 3 || !current.equals(previous) || now - lastAt >= 750L;
    }
}

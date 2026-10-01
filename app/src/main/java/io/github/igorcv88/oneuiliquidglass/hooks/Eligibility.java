package io.github.igorcv88.oneuiliquidglass.hooks;

/** Unknown firmware states retain native rendering. */
public final class Eligibility {
    private Eligibility() {}
    public static boolean glass(boolean enabled, Boolean headsUp, Boolean keyguard,
                                Boolean shadeExpanded, boolean attached, boolean hardware, boolean interacting) {
        return enabled && Boolean.TRUE.equals(headsUp) && Boolean.FALSE.equals(keyguard)
                && Boolean.FALSE.equals(shadeExpanded) && attached && hardware && !interacting;
    }
}

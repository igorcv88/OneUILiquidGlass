package io.github.igorcv88.oneuiliquidglass.glass;

/** Pure-Java limits for the Samsung shade-window blur; never changes lockscreen blur. */
public final class PanelBlurPolicy {
    private PanelBlurPolicy() {}
    /** -1 follows the firmware; a positive cap preserves the opening's native ramp. */
    public static int radius(int nativeRadius, int cap) {
        if(nativeRadius < 0) return nativeRadius;
        return cap < 0 ? nativeRadius : Math.min(Math.max(0, nativeRadius), cap);
    }
}

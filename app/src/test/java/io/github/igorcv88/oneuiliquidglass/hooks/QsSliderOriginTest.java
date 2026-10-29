package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class QsSliderOriginTest {
    @Test public void onlyUnambiguousSamsungCallerDeterminesRole() {
        assertEquals("brightness",QsSliderOrigin.identify(stack("com.android.systemui.BrightnessSliderKt")));
        assertEquals("volume",QsSliderOrigin.identify(stack("com.samsung.systemui.VolumeSliderKt")));
        assertEquals("unknown",QsSliderOrigin.identify(stack("com.android.systemui.GenericBar")));
        assertEquals("unknown",QsSliderOrigin.identify(new StackTraceElement[] {
                new StackTraceElement("com.samsung.BrightnessBar","render","a",12),
                new StackTraceElement("com.samsung.VolumeBar","render","b",15)
        }));
    }
    @Test public void volumeMustNotSuppressButUnresolvedBrightnessPreservesCurrentSafety() {
        assertFalse(QsSliderOrigin.suppress(QsSliderOrigin.VOLUME));
        assertTrue(QsSliderOrigin.suppress(QsSliderOrigin.BRIGHTNESS));
        assertTrue(QsSliderOrigin.suppress(QsSliderOrigin.UNKNOWN));
    }
    @Test public void originOnlyContainsMethodAndClassNames() {
        String hint=QsSliderOrigin.stackHint(stack("com.android.systemui.BrightnessSliderKt"));
        assertTrue(hint.contains("BrightnessSliderKt#compose"));
    }
    private static StackTraceElement[] stack(String clazz) {
        return new StackTraceElement[]{new StackTraceElement(clazz,"compose","Firmware.java",14)};
    }
}

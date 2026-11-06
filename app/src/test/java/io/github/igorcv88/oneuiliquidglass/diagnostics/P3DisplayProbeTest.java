package io.github.igorcv88.oneuiliquidglass.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class P3DisplayProbeTest {
    @Test
    public void parsePhysicalDisplayIdFromSamsungDzif() {
        assertEquals(4630947191136750483L,
                P3DisplayProbe.physicalDisplayId("local:4630947191136750483"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectNonPhysicalDisplay() {
        P3DisplayProbe.physicalDisplayId("virtual:com.android.systemui");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectInvalidId() {
        P3DisplayProbe.physicalDisplayId("local:1234com.instagram");
    }
}

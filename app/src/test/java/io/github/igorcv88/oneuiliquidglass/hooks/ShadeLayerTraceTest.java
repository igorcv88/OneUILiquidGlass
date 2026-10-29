package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadeLayerTraceTest {
    @Test public void identifiesNativeBackgroundsByIdentityNotByAppearance() {
        Object normal = new Object(), dimmed = new Object(), other = new Object();
        assertEquals("normal", ShadeLayerTrace.role(normal, normal, dimmed));
        assertEquals("dimmed", ShadeLayerTrace.role(dimmed, normal, dimmed));
        assertEquals("other", ShadeLayerTrace.role(other, normal, dimmed));
        assertEquals("unknown", ShadeLayerTrace.role(null, normal, dimmed));
        assertEquals("both", ShadeLayerTrace.role(normal, normal, normal));
    }

    @Test public void capturesInitialDrawsAndStateChangesWithoutFloodingFrames() {
        assertTrue(ShadeLayerTrace.emit(1, "glass", "glass", 10, 10));
        assertTrue(ShadeLayerTrace.emit(3, "glass", "glass", 10, 10));
        assertFalse(ShadeLayerTrace.emit(4, "glass", "glass", 400, 10));
        assertTrue(ShadeLayerTrace.emit(4, "native", "glass", 400, 10));
        assertTrue(ShadeLayerTrace.emit(4, "glass", "glass", 760, 10));
    }
}

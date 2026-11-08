package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TraceOptInTest {
    @Test public void rootPropertyCanEnableTraceDespiteSavedPreferenceOff() {
        assertTrue(Tuning.traceEnabled(false, "1"));
    }

    @Test public void savedPreferenceStillEnablesTrace() {
        assertTrue(Tuning.traceEnabled(true, "0"));
    }

    @Test public void traceStaysOffWithoutEitherOptIn() {
        assertFalse(Tuning.traceEnabled(false, "0"));
        assertFalse(Tuning.traceEnabled(false, ""));
    }
}

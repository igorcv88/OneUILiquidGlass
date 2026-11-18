package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

/** R5 4.4: the per-box layer isolation is opt-in, selective by token id, and ends by itself. */
public class QsLayerIsolationTest {
    @Test public void onlyAWellFormedTargetArmsIt() {
        for (String bad : new String[] {null, "", "2145", ":1", "2145:", "0:1", "2145:0", "2145:8", "x:1", "2145:a"})
            assertNull(bad, QsLayerIsolation.parse(bad));
        assertArrayEquals(new long[] {2145, 5}, QsLayerIsolation.parse(" 2145 : 5 "));
        QsLayerIsolation i = new QsLayerIsolation();
        assertNull(i.arm("", 0));
        assertFalse(i.active(0));
        assertFalse(i.off(2145, QsLayerIsolation.AGSL, 0));
    }

    @Test public void eachBitRemovesOneLayerOfTheTargetOnly() {
        QsLayerIsolation i = new QsLayerIsolation();
        String line = i.arm("2145:2", 1000);
        assertTrue(line, line.contains("agsl=off") && line.contains("sfProfile3=on") && line.contains("composeRim=on"));
        assertTrue(i.off(2145, QsLayerIsolation.AGSL, 1000));
        assertFalse("other layers stay", i.off(2145, QsLayerIsolation.SF_PROFILE3, 1000));
        assertFalse("other boxes stay", i.off(2917, QsLayerIsolation.AGSL, 1000));
        Object box = new Object(), other = new Object();
        i.arm("2145:4", 1000);
        assertFalse("box unknown until its token reports", i.rimOff(box, 1000));
        i.learn(2917, other);
        i.learn(2145, box);
        assertTrue(i.rimOff(box, 1000));
        assertFalse(i.rimOff(other, 1000));
    }

    @Test public void theWindowEndsByItselfAndDisarmingClearsIt() {
        QsLayerIsolation i = new QsLayerIsolation();
        i.arm("7:1", 1000);
        assertTrue(i.off(7, QsLayerIsolation.SF_PROFILE3, 1000 + QsLayerIsolation.WINDOW_MS - 1));
        assertFalse(i.off(7, QsLayerIsolation.SF_PROFILE3, 1000 + QsLayerIsolation.WINDOW_MS));
        i.arm("7:1", 5000);
        assertTrue(i.active(5000));
        i.arm("", 6000);
        assertFalse(i.active(6000));
        assertTrue(i.firstHit(1));
        assertFalse("one line per layer", i.firstHit(1));
    }
}

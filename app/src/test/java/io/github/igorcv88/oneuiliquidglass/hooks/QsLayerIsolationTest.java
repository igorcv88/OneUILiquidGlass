package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * R5 4.4: the layer isolation is opt-in, selects surfaces by persistent traits (role, inner size)
 * or a session id, and ends by itself. The first device run armed id 710 (the landscape panel of
 * an earlier session) and matched nothing: a size or role selector survives sessions.
 */
public class QsLayerIsolationTest {
    @Test public void malformedSelectorsDisarm() {
        for (String bad : new String[] {null, "", "2145", ":1", "2145:", "0:1", "2145:0", "2145:8", "x:1",
                "2145:a", "tile:1", "container@:1", "container@12x:1", "0x5:1", "foo@652x282:1"})
            assertNull(bad, QsLayerIsolation.parse(bad));
        QsLayerIsolation i = new QsLayerIsolation();
        assertNull(i.arm("", 0));
        assertFalse(i.active(0));
    }

    @Test public void sizeRoleAndIdSelectTheSurface() {
        QsLayerIsolation.Selector panel = QsLayerIsolation.parse("1336x909:2");
        assertTrue(panel.matches(450, "container", 1336, 909));
        assertTrue("any session id, +-3 px", panel.matches(2145, "container", 1338, 907));
        assertFalse(panel.matches(450, "container", 652, 282));
        QsLayerIsolation.Selector caps = QsLayerIsolation.parse("container@652x282:4");
        assertTrue(caps.matches(446, "container", 652, 282));
        assertTrue(caps.matches(447, "container", 652, 282));
        assertFalse("role must match", caps.matches(452, "slider", 652, 282));
        QsLayerIsolation.Selector sliders = QsLayerIsolation.parse("slider:1");
        assertTrue(sliders.matches(452, "slider", 268, 595));
        assertFalse(sliders.matches(450, "container", 1336, 909));
        QsLayerIsolation.Selector id = QsLayerIsolation.parse(" 710 : 1 ");
        assertTrue(id.matches(710, "container", 1912, 223));
        assertFalse(id.matches(450, "container", 1336, 909));
    }

    @Test public void eachBitRemovesOneLayerAndOutlinesFollowLearnedBoxes() {
        QsLayerIsolation i = new QsLayerIsolation();
        String line = i.arm("1336x909:2", 1000);
        assertTrue(line, line.contains("agsl=off") && line.contains("sfProfile3=on") && line.contains("composeRim=on"));
        assertTrue(i.off(450, "container", 1336, 909, QsLayerIsolation.AGSL, 1000));
        assertFalse("other layers stay", i.off(450, "container", 1336, 909, QsLayerIsolation.SF_PROFILE3, 1000));
        assertFalse("other boxes stay", i.off(446, "container", 652, 282, QsLayerIsolation.AGSL, 1000));
        Object box = new Object(), other = new Object();
        assertFalse("no outline bit: nothing to learn", i.learn(box));
        i.arm("1336x909:4", 1000);
        assertFalse("box unknown until it matches", i.rimOff(box, 1000));
        assertTrue("first match asks for one recomposition", i.learn(box));
        assertFalse(i.learn(box));
        assertTrue(i.rimOff(box, 1000));
        assertFalse(i.rimOff(other, 1000));
    }

    @Test public void theWindowEndsByItselfAndDisarmingClearsIt() {
        QsLayerIsolation i = new QsLayerIsolation();
        i.arm("container:1", 1000);
        assertTrue(i.off(7, "container", 10, 10, QsLayerIsolation.SF_PROFILE3, 1000 + QsLayerIsolation.WINDOW_MS - 1));
        assertFalse(i.off(7, "container", 10, 10, QsLayerIsolation.SF_PROFILE3, 1000 + QsLayerIsolation.WINDOW_MS));
        i.arm("container:5", 5000);
        Object box = new Object();
        assertTrue(i.learn(box));
        i.arm("", 6000);
        assertFalse(i.active(6000));
        assertFalse("disarm forgets learned boxes", i.rimOff(box, 6000));
        assertTrue(i.firstHit(1));
        assertFalse("one line per layer", i.firstHit(1));
    }
}

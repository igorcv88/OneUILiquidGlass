package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QsBackdropTest {
    /** A VISIBLE compose root over a collapsed panel is not an opening; expansion decides. */
    @Test public void openingFollowsTheExpansionNotTheVisibility() {
        assertFalse(QsBackdrop.open(true, 0));
        assertTrue(QsBackdrop.open(true, 0.02));
        assertTrue(QsBackdrop.open(true, 1));
        assertFalse(QsBackdrop.open(false, 1));
        // Unreadable expansion: visibility stands in for it.
        assertTrue(QsBackdrop.open(true, -1));
        assertFalse(QsBackdrop.open(false, -1));
    }
    /** In-session: white to black behind the open panel needs a confirming capture; noise is ignored. */
    @Test public void sessionChangesAreConfirmedAndNoiseIsIgnored() {
        io.github.igorcv88.oneuiliquidglass.glass.QsScene scene = new io.github.igorcv88.oneuiliquidglass.glass.QsScene();
        io.github.igorcv88.oneuiliquidglass.glass.ToneGate gate =
                new io.github.igorcv88.oneuiliquidglass.glass.ToneGate(QsBackdrop.CONFIRM_MS, QsBackdrop.DWELL_MS);
        assertTrue("first decision", QsBackdrop.offer(scene, gate, .9f, 0));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.LIGHT_SCENE, scene.tone());
        assertFalse("hysteresis band", QsBackdrop.offer(scene, gate, .70f, 1000));
        assertFalse(gate.pending());
        assertFalse("one dark capture", QsBackdrop.offer(scene, gate, .1f, 2000));
        assertTrue(gate.pending());
        assertFalse("noise back to light", QsBackdrop.offer(scene, gate, .9f, 2100));
        assertFalse(gate.pending());
        assertFalse(QsBackdrop.offer(scene, gate, .1f, 3000));
        assertTrue("confirmed", QsBackdrop.offer(scene, gate, .1f, 3000 + QsBackdrop.CONFIRM_MS));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.DARK_SCENE, scene.tone());
        assertFalse("invalid sample", QsBackdrop.offer(scene, gate, Float.NaN, 5000));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.DARK_SCENE, scene.tone());
    }
}

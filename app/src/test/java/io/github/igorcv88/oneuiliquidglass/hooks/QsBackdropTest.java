package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QsBackdropTest {
    @Test public void taskSwitchUsesBurstThenReturnsToIdleWithoutPerpetualPolling() {
        long taskAt=10_000, lastTouch=9_900;
        assertEquals(QsBackdrop.BURST_RESAMPLE_MS, QsBackdrop.resampleInterval(taskAt,taskAt,lastTouch));
        assertEquals(QsBackdrop.BURST_RESAMPLE_MS, QsBackdrop.resampleInterval(taskAt+599,taskAt,lastTouch));
        assertEquals(QsBackdrop.ACTIVE_RESAMPLE_MS, QsBackdrop.resampleInterval(taskAt+600,taskAt,lastTouch));
        assertEquals(QsBackdrop.RESAMPLE_MS, QsBackdrop.resampleInterval(taskAt+1500,taskAt,lastTouch));
        assertEquals(QsBackdrop.RESAMPLE_MS, QsBackdrop.resampleInterval(taskAt,taskAt+1,lastTouch-3000));
    }
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

    @Test public void onlyFreshShadeCaptureMaySeedTheLateralOpening() {
        long sampled = 10_000;
        assertTrue(QsBackdrop.shadeHandoff(1.0, false, sampled + 140, sampled, sampled - 1));
        assertTrue(QsBackdrop.shadeHandoff(.95, false, sampled + 250, sampled, sampled));
        assertFalse("direct pull-down", QsBackdrop.shadeHandoff(.03, false, sampled + 140, sampled, sampled - 1));
        assertFalse("own gesture capture already in flight", QsBackdrop.shadeHandoff(1.0, true, sampled + 140, sampled, sampled - 1));
        assertFalse("stale previous screen", QsBackdrop.shadeHandoff(1.0, false, sampled + 251, sampled, sampled - 1));
        assertFalse("app changed since shade capture", QsBackdrop.shadeHandoff(1.0, false, sampled + 140, sampled, sampled + 1));
        assertFalse("future capture timestamp", QsBackdrop.shadeHandoff(1.0, false, sampled - 1, sampled, 0));
        assertFalse("no shade measurement", QsBackdrop.shadeHandoff(1.0, false, 500, 0, 0));
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
        // A weak reading (near the boundary) needs its confirming capture; noise drops it.
        assertFalse("one weak dark capture", QsBackdrop.offer(scene, gate, .55f, 2000));
        assertTrue(gate.pending());
        assertFalse("noise back to light", QsBackdrop.offer(scene, gate, .9f, 2100));
        assertFalse(gate.pending());
        assertFalse(QsBackdrop.offer(scene, gate, .55f, 3000));
        assertTrue("confirmed", QsBackdrop.offer(scene, gate, .55f, 3000 + QsBackdrop.CONFIRM_MS));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.DARK_SCENE, scene.tone());
        // A strong reading (white page after a dark one) applies with that capture.
        assertTrue("strong", QsBackdrop.offer(scene, gate, .98f, 4000));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.LIGHT_SCENE, scene.tone());
        assertFalse("invalid sample", QsBackdrop.offer(scene, gate, Float.NaN, 5000));
        assertEquals(io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.LIGHT_SCENE, scene.tone());
    }
}


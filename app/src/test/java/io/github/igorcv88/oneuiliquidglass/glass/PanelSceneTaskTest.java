package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

/** QS113: the task-switch quick confirmation and its limits (same-app content, stale and mixed frames). */
public class PanelSceneTaskTest {
    private static ToneGrid uniform(float luma, long at) {
        float[] bands = new float[32];
        java.util.Arrays.fill(bands, luma);
        return new ToneGrid(0, 10, bands, bands, -1, at);
    }
    /** Mean ~luma, but only {@code lightBands} of 32 on the bright side: a transition frame. */
    private static ToneGrid mixed(int lightBands, long at) {
        float[] bands = new float[32];
        for (int i = 0; i < bands.length; i++) bands[i] = i < lightBands ? 1f : .45f;
        return new ToneGrid(0, 10, bands, bands, -1, at);
    }

    @Test public void taskSwitchToWhiteCommitsOnSecondConsistentSampleNotAfterDwell() {
        // The Galaxy trace of 31/10: .412 -> .754 -> .780 took ~370 ms to commit LIGHT_SCENE.
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.412f, 1000));
        scene.task(1100);
        PanelScene.Snapshot first = scene.accept(uniform(.754f, 1150));
        assertEquals(QsScene.Tone.DARK_SCENE, first.body);
        assertEquals("candidate", first.bodyReason);
        PanelScene.Snapshot second = scene.accept(uniform(.780f, 1275));
        assertEquals(QsScene.Tone.LIGHT_SCENE, second.body);
        assertEquals("task-confirmed", second.bodyReason);
    }

    @Test public void withoutTaskEventTheSameReadingsStillWaitForTheDwell() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.412f, 1000));
        scene.accept(uniform(.754f, 1150));
        PanelScene.Snapshot held = scene.accept(uniform(.780f, 1275));
        assertEquals(QsScene.Tone.DARK_SCENE, held.body);
        assertEquals("hold-confirm", held.bodyReason);
        // Past confirmMs, but within the 600 ms dwell after the first decision at 1000.
        PanelScene.Snapshot dwell = scene.accept(uniform(.780f, 1400));
        assertEquals(QsScene.Tone.DARK_SCENE, dwell.body);
        assertEquals("hold-dwell", dwell.bodyReason);
        PanelScene.Snapshot done = scene.accept(uniform(.780f, 1600));
        assertEquals(QsScene.Tone.LIGHT_SCENE, done.body);
        assertEquals("confirmed", done.bodyReason);
    }

    @Test public void samplesTakenBeforeTheTaskEventDoNotUseTheQuickPath() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.412f, 1000));
        scene.task(1300);
        scene.accept(uniform(.754f, 1150));
        assertEquals(QsScene.Tone.DARK_SCENE, scene.accept(uniform(.78f, 1275)).body);
    }

    @Test public void quickWindowExpires() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.412f, 1000));
        scene.task(1000);
        scene.accept(uniform(.754f, 1700));
        assertEquals(QsScene.Tone.DARK_SCENE, scene.accept(uniform(.78f, 1825)).body);
    }

    @Test public void mixedTransitionFrameIsNotSpatiallyConsistent() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.3f, 1000));
        scene.task(1100);
        // 27 of 32 bands white: mean .915 is strong on its own and passes.
        assertEquals(QsScene.Tone.LIGHT_SCENE, scene.accept(mixed(27, 1150)).body);
        PanelScene other = new PanelScene();
        other.accept(uniform(.3f, 1000));
        other.task(1100);
        // 20 of 32 white: mean ~.79, light by mean, yet only 62% of bands agree.
        other.accept(mixed(20, 1150));
        assertEquals(QsScene.Tone.DARK_SCENE, other.accept(mixed(20, 1275)).body);
    }

    @Test public void transientFlashInSameAppStillNeedsConfirmationAndAgreeingSampleDropsIt() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.3f, 1000));
        scene.accept(uniform(.77f, 2000));
        assertEquals(QsScene.Tone.DARK_SCENE, scene.accept(uniform(.3f, 2125)).body);
        assertEquals(QsScene.Tone.DARK_SCENE, scene.accept(uniform(.77f, 2250)).body);
    }

    @Test public void candidateSeenBeforeTheTaskEventNeedsTwoPostEventSamples() {
        PanelScene scene = new PanelScene();
        scene.accept(uniform(.3f, 1000));
        scene.accept(uniform(.77f, 2000));      // in-app candidate, before the event
        scene.task(2050);
        PanelScene.Snapshot reseeded = scene.accept(uniform(.77f, 2100));
        assertEquals(QsScene.Tone.DARK_SCENE, reseeded.body);
        assertEquals("candidate", reseeded.bodyReason);
        assertEquals(QsScene.Tone.LIGHT_SCENE, scene.accept(uniform(.77f, 2225)).body);
    }
}

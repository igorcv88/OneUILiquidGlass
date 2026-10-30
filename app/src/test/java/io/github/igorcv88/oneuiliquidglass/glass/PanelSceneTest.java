package io.github.igorcv88.oneuiliquidglass.glass;
import org.junit.Test;
import static org.junit.Assert.*;
public class PanelSceneTest {
    private ToneGrid grid(float luma,long at){return new ToneGrid(0,100,new float[]{luma},new float[]{luma},-1,at);}
    @Test public void consumersShareOneDecisionAndLateFramesCannotRewindIt(){
        PanelScene scene=new PanelScene();
        PanelScene.Snapshot white=scene.accept(grid(.98f,1000));
        assertEquals(QsScene.Tone.LIGHT_SCENE,white.body);
        assertSame(white,scene.accept(grid(.1f,999)));
        assertSame(white,scene.accept(grid(.1f,1000)));
        PanelScene.Snapshot black=scene.accept(grid(.08f,1125));
        assertEquals(QsScene.Tone.DARK_SCENE,black.body);
        assertSame(black,scene.accept(black.grid.forGeneration(123)));
    }
    @Test public void weakTransientDoesNotFlipButConfirmedSceneDoes(){
        PanelScene scene=new PanelScene();scene.accept(grid(.98f,1000));
        assertEquals(QsScene.Tone.LIGHT_SCENE,scene.accept(grid(.55f,1600)).body);
        assertEquals(QsScene.Tone.LIGHT_SCENE,scene.accept(grid(.9f,1725)).body);
        assertEquals(QsScene.Tone.LIGHT_SCENE,scene.accept(grid(.55f,1850)).body);
        assertEquals(QsScene.Tone.DARK_SCENE,scene.accept(grid(.55f,2100)).body);
    }
    @Test public void invalidSamplesAndInterruptedCaptureDoNotReuseOldHysteresis(){
        PanelScene scene=new PanelScene();
        assertNull(scene.accept(grid(Float.NaN,1)));
        scene.accept(grid(.95f,1000));
        assertEquals(QsScene.Tone.DARK_SCENE,scene.accept(grid(.7f,2200)).body);
        scene.clear();assertNull(scene.accept(null));
        assertEquals(QsScene.Tone.LIGHT_SCENE,scene.accept(grid(1f,2300)).body);
    }
}

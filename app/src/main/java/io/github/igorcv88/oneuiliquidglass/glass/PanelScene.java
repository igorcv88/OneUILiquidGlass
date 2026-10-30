package io.github.igorcv88.oneuiliquidglass.glass;

/** One immutable decision per captured frame, shared by both panels on the main thread. */
public final class PanelScene {
    /** A clearly bright page is safe to classify before the second 100 ms QS capture. */
    static final float FAST_LIGHT_LUMA = .80f;
    public static final class Snapshot {
        public final ToneGrid grid;
        public final QsScene.Tone body, header;
        Snapshot(ToneGrid g,QsScene.Tone b,QsScene.Tone h){grid=g;body=b;header=h;}
    }
    private final ToneGate body=new ToneGate(150,600), header=new ToneGate(150,600);
    private Snapshot latest;
    public Snapshot accept(ToneGrid grid) {
        if(grid==null||!Float.isFinite(grid.whole())||grid.whole()<0||grid.whole()>1)return latest;
        if(latest!=null&&grid.takenAt<=latest.grid.takenAt)return latest;
        // After an interruption there is no evidence of continuity with the old scene.
        if(latest==null||grid.takenAt-latest.grid.takenAt>1000){body.reset();header.reset();}
        QsScene.Tone b=decide(body,grid.whole(),grid.takenAt);
        QsScene.Tone h=decide(header,grid.luma(grid.top,grid.top+4*grid.band),grid.takenAt);
        latest=new Snapshot(grid,b,h);return latest;
    }
    private static QsScene.Tone decide(ToneGate gate,float luma,long at) {
        QsScene.Tone previous=gate.tone()==null?QsScene.Tone.UNKNOWN:gate.tone()?QsScene.Tone.LIGHT_SCENE:QsScene.Tone.DARK_SCENE;
        QsScene.Tone next=QsScene.classify(luma,previous);
        if(next==QsScene.Tone.UNKNOWN)return previous;
        // The DZIF task transition often first shows a .80-.87 white frame. The original
        // .20 strong-distance threshold made the bright direction wait 150-300 ms longer
        // than the dark direction (observed on the Galaxy, 30/10); keep the dwell guard.
        boolean strong = ToneGate.strong(luma,QsScene.MID)
                || next == QsScene.Tone.LIGHT_SCENE && luma >= FAST_LIGHT_LUMA;
        gate.offer(next==QsScene.Tone.LIGHT_SCENE,at,strong);
        return gate.tone()?QsScene.Tone.LIGHT_SCENE:QsScene.Tone.DARK_SCENE;
    }
    public void clear(){latest=null;body.reset();header.reset();}
}

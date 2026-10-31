package io.github.igorcv88.oneuiliquidglass.glass;

/** One immutable decision per captured frame, shared by both panels on the main thread. */
public final class PanelScene {
    /** A clearly bright page is safe to classify before the second 100 ms QS capture. */
    static final float FAST_LIGHT_LUMA = .80f;
    /**
     * After a task event (BackdropEvents: onTaskMovedToFront, onTaskFocusChanged...), samples
     * taken within this window may confirm a weak change with the next consistent sample
     * (ToneGate quick path). Same length as the QS burst sampling window.
     */
    static final long TASK_QUICK_MS = 600;
    /** Share of bands that must lie on the new side of {@link QsScene#MID} for the quick path. */
    static final float CONSISTENT = 2f / 3f;

    public static final class Snapshot {
        public final ToneGrid grid;
        public final QsScene.Tone body, header;
        /** ToneGate's reason for each decision (constant strings), for the trace. */
        public final String bodyReason, headerReason;
        /** The sample's luma that produced the decision (whole capture, header band). */
        public final float bodyLuma, headerLuma;
        Snapshot(ToneGrid g, QsScene.Tone b, QsScene.Tone h, String br, String hr, float bl, float hl) {
            grid = g; body = b; header = h; bodyReason = br; headerReason = hr; bodyLuma = bl; headerLuma = hl;
        }
    }
    private final ToneGate body=new ToneGate(150,600), header=new ToneGate(150,600);
    private Snapshot latest;
    /** Uptime of the last task event; written from SystemUI's task listener thread. */
    private volatile long taskAt = Long.MIN_VALUE / 2;

    /** A task came to the front: a priority for the next captures, never a guess of their colour. */
    public void task(long at) { taskAt = at; }

    public Snapshot accept(ToneGrid grid) {
        if(grid==null||!Float.isFinite(grid.whole())||grid.whole()<0||grid.whole()>1)return latest;
        if(latest!=null&&grid.takenAt<=latest.grid.takenAt)return latest;
        // After an interruption there is no evidence of continuity with the old scene.
        if(latest==null||grid.takenAt-latest.grid.takenAt>1000){body.reset();header.reset();}
        long task = taskAt;
        // Only a sample taken after the event qualifies: a capture requested before the switch
        // shows the old app and must never confirm (nor revert) the new scene.
        boolean afterTask = grid.takenAt >= task && grid.takenAt - task <= TASK_QUICK_MS;
        int headerEnd = grid.top + 4 * grid.band;
        float bodyLuma = grid.whole(), headerLuma = grid.luma(grid.top, headerEnd);
        QsScene.Tone b=decide(body,bodyLuma,grid.takenAt,afterTask,task,grid,grid.top,Integer.MAX_VALUE);
        QsScene.Tone h=decide(header,headerLuma,grid.takenAt,afterTask,task,grid,grid.top,headerEnd);
        latest=new Snapshot(grid,b,h,body.lastReason(),header.lastReason(),bodyLuma,headerLuma);return latest;
    }
    private static QsScene.Tone decide(ToneGate gate,float luma,long at,boolean afterTask,long task,ToneGrid grid,int y0,int y1) {
        QsScene.Tone previous=gate.tone()==null?QsScene.Tone.UNKNOWN:gate.tone()?QsScene.Tone.LIGHT_SCENE:QsScene.Tone.DARK_SCENE;
        QsScene.Tone next=QsScene.classify(luma,previous);
        if(next==QsScene.Tone.UNKNOWN)return previous;
        boolean light = next == QsScene.Tone.LIGHT_SCENE;
        // The DZIF task transition often first shows a .80-.87 white frame. The original
        // .20 strong-distance threshold made the bright direction wait 150-300 ms longer
        // than the dark direction (observed on the Galaxy, 30/10); keep the dwell guard.
        boolean strong = ToneGate.strong(luma,QsScene.MID)
                || light && luma >= FAST_LIGHT_LUMA;
        // QS113 (31/10 trace): .412 -> .754 -> .780 after a task switch is "weak" light
        // (below .80 and .90), so it waited for confirmMs and the 600 ms dwell: ~370 ms. The
        // dark direction is strong at <= .50 and rarely waits, hence the asymmetry. Within the
        // task window a consistent reading needs only the next sample; in-app content
        // (video, flash, scroll: no task event) keeps the full hysteresis.
        boolean quick = afterTask && previous != next && consistent(grid, y0, y1, light);
        gate.offer(light,at,strong,quick?task:Long.MAX_VALUE);
        return gate.tone()?QsScene.Tone.LIGHT_SCENE:QsScene.Tone.DARK_SCENE;
    }
    /** Whether at least {@link #CONSISTENT} of the bands in [y0, y1) lie on {@code light}'s side of MID. */
    static boolean consistent(ToneGrid grid, int y0, int y1, boolean light) {
        float[] v = grid.mean;
        int agree = 0, total = 0;
        for (int i = 0; i < v.length; i++) {
            int top = grid.top + i * grid.band;
            if (top + grid.band <= y0 || top >= y1) continue;
            total++;
            if (light ? v[i] >= QsScene.MID : v[i] < QsScene.MID) agree++;
        }
        return total > 0 && agree >= CONSISTENT * total;
    }
    public void clear(){latest=null;body.reset();header.reset();}
}

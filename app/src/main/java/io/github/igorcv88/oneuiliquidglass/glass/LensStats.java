package io.github.igorcv88.oneuiliquidglass.glass;

import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the lens band of LSPosed-only pop-ups did, logged as LENS_STATS every 240 draws and when a
 * band is dropped: how many draws showed it, why the others did not (BackdropMotion.block), and the
 * age of the capture it drew. That age runs from the capture request to the draw that records it; it
 * is not the age of the pixels on the display, which reach it one or two vsyncs later (RenderThread,
 * then SurfaceFlinger). A blocked draw never draws the band. Main thread only.
 */
final class LensStats {
    private LensStats() {}
    private static final List<Long> ages = new ArrayList<>();
    private static final Map<String, Integer> blocked = new TreeMap<>();
    private static int draws, shown;

    /** drawn: whether this draw put captured pixels on screen (any weight). */
    static void note(String block, long ageMs, boolean drawn) {
        draws++;
        if (drawn) { shown++; if (ageMs >= 0) ages.add(ageMs); }
        if (block != null) blocked.merge(block, 1, Integer::sum);
        if (draws >= 240) flush("periodic");
    }

    static void flush(String why) {
        if (draws == 0) return;
        Probe.log("LENS_STATS", "reason=" + why + " draws=" + draws + " shown=" + shown + " blocked=" + blocked
                + " requestToDrawP50=" + pct(50) + " requestToDrawP95=" + pct(95) + " requestToDrawMax=" + pct(100) + " signal=" + BackdropMotion.active()
                + " gate=" + Tuning.get().lensGate + " gestures=tap:" + BackdropMotion.gestures[BackdropMotion.PENDING]
                + ",vertical:" + BackdropMotion.gestures[BackdropMotion.VERTICAL] + ",horizontal:" + BackdropMotion.gestures[BackdropMotion.HORIZONTAL]);
        java.util.Arrays.fill(BackdropMotion.gestures, 0);
        ages.clear(); blocked.clear(); draws = 0; shown = 0;
    }

    private static String pct(int p) {
        if (ages.isEmpty()) return "na";
        List<Long> sorted = new ArrayList<>(ages);
        Collections.sort(sorted);
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return String.valueOf(sorted.get(Math.max(0, Math.min(sorted.size() - 1, i))));
    }
}

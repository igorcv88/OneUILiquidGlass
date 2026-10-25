package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * Luminance of the backdrop behind the shade, in horizontal bands, from one capture taken as the
 * shade starts to open (ShadeTone.prefetch). A card reads the bands it covers, wherever it is on
 * screen at that moment: the same capture serves every card, and a card that slides or scrolls
 * over a brighter part of the backdrop reads that part. Values are 0-1 sRGB luma.
 */
public final class ToneGrid {
    /** Screen y of the first band's top, and the height of each band, in px. */
    public final int top, band;
    /** Mean and 90th percentile luma of each band. */
    final float[] mean, p90;
    /** The shade opening this capture belongs to (HeadsUpHooks.shadeOpens). */
    public final int generation;

    ToneGrid(int top, int band, float[] mean, float[] p90, int generation) {
        this.top = top; this.band = Math.max(1, band); this.mean = mean; this.p90 = p90; this.generation = generation;
    }

    /** Mean luma under the screen rows [y0, y1), each band weighted by its overlap; -1 if none. */
    public float luma(int y0, int y1) { return over(mean, y0, y1); }
    /** The same for the bands' 90th percentile (bright spots under the text), for the log. */
    public float bright(int y0, int y1) { return over(p90, y0, y1); }

    private float over(float[] v, int y0, int y1) {
        int lo = Math.max(y0, top), hi = Math.min(y1, top + band * v.length);
        if (hi <= lo) {
            // Off the captured rows (above the status bar, below the screen): the nearest band.
            if (v.length == 0) return -1f;
            return v[y1 <= top ? 0 : v.length - 1];
        }
        double sum = 0;
        int weight = 0;
        for (int i = (lo - top) / band; i < v.length && top + i * band < hi; i++) {
            int a = Math.max(lo, top + i * band), b = Math.min(hi, top + (i + 1) * band);
            if (b <= a) continue;
            sum += v[i] * (double) (b - a);
            weight += b - a;
        }
        return weight == 0 ? -1f : (float) (sum / weight);
    }
}

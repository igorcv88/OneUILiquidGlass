package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * debug.oulg.lensgate=region: whether the pixels behind a pop-up changed between two captures. Each
 * capture is reduced to the mean luminance of CELL x CELL screen-pixel cells on a grid anchored at
 * the screen origin, so two captures with different crops (a card sliding in) still compare cell by
 * cell. Only cells entirely inside the capture are defined. Pure Java; the readback that feeds it
 * runs on CaptureHub's region worker.
 *
 * <p>A change is seen only once a capture shows it: the first moving frame behind the card is
 * already on screen in the band when the verdict arrives. That onset is the cost of this gate.</p>
 */
public final class RegionDiff {
    private RegionDiff() {}

    /** Cell side in screen px. */
    static final int CELL = 16;
    static final int STILL = 0, CHANGED = 1, UNKNOWN = 2;

    /** Cell means (0-255) of one capture; NaN where the capture does not cover the whole cell. */
    public static final class Signature {
        final int col0, row0, cols, rows;
        final float[] cells;
        final float scale;
        /** CaptureHub.Frame.capturedAt of the capture it was taken from. */
        public final long capturedAt;
        Signature(int col0, int row0, int cols, int rows, float[] cells, float scale, long capturedAt) {
            this.col0 = col0; this.row0 = row0; this.cols = cols; this.rows = rows; this.cells = cells; this.scale = scale; this.capturedAt = capturedAt;
        }
        float at(int col, int row) {
            int c = col - col0, r = row - row0;
            return c < 0 || r < 0 || c >= cols || r >= rows ? Float.NaN : cells[r * cols + c];
        }
    }

    /**
     * Signature of a capture: {@code px} (ARGB, w x h) is the screen crop starting at
     * (cropLeft, cropTop) scaled by {@code scale}. Every second bitmap pixel of a cell is sampled.
     */
    public static Signature signature(int[] px, int w, int h, int cropLeft, int cropTop, float scale, long capturedAt) {
        int cropRight = cropLeft + Math.round(w / scale), cropBottom = cropTop + Math.round(h / scale);
        int col0 = Math.floorDiv(cropLeft + CELL - 1, CELL), row0 = Math.floorDiv(cropTop + CELL - 1, CELL);
        int cols = Math.max(0, Math.floorDiv(cropRight, CELL) - col0), rows = Math.max(0, Math.floorDiv(cropBottom, CELL) - row0);
        float[] cells = new float[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int sx = (col0 + c) * CELL, sy = (row0 + r) * CELL;
                int x0 = (int) Math.floor((sx - cropLeft) * scale), y0 = (int) Math.floor((sy - cropTop) * scale);
                int x1 = Math.min(w, (int) Math.ceil((sx + CELL - cropLeft) * scale)), y1 = Math.min(h, (int) Math.ceil((sy + CELL - cropTop) * scale));
                double sum = 0; int n = 0;
                for (int y = Math.max(0, y0); y < y1; y += 2) {
                    for (int x = Math.max(0, x0); x < x1; x += 2) { sum += luma(px[y * w + x]); n++; }
                }
                cells[r * cols + c] = n == 0 ? Float.NaN : (float) (sum / n);
            }
        }
        return new Signature(col0, row0, cols, rows, cells, scale, capturedAt);
    }

    static float luma(int c) {
        return (float) (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255));
    }

    /**
     * Whether the backdrop under the screen rect [left, right) x [top, bottom) changed from {@code a}
     * to {@code b}: CHANGED if any cell the two share inside it differs by more than {@code threshold}
     * (0-255), UNKNOWN if they share none or were taken at different scales, else STILL.
     */
    public static int compare(Signature a, Signature b, int left, int top, int right, int bottom, float threshold) {
        if (a == null || b == null || a.scale != b.scale) return UNKNOWN;
        int c0 = Math.floorDiv(left, CELL), c1 = Math.floorDiv(right - 1, CELL);
        int r0 = Math.floorDiv(top, CELL), r1 = Math.floorDiv(bottom - 1, CELL);
        boolean any = false;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                float x = a.at(c, r), y = b.at(c, r);
                if (Float.isNaN(x) || Float.isNaN(y)) continue;
                if (Math.abs(x - y) > threshold) return CHANGED;
                any = true;
            }
        }
        return any ? STILL : UNKNOWN;
    }
}

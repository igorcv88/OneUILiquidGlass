package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which glass containers may draw the attenuated outline: only a box whose own background node
 * drew the AGSL optics. A box is its BoxWithOutline restart scope (stable across recompositions,
 * and the scope both its border and its background are built in), so the border element, built
 * before any draw, reads the state its previous background draw left. Until a draw of this box
 * succeeds the outline stays at full strength; a failed draw hands the full outline back and is
 * not retried in the same panel session (no full/attenuated oscillation per frame). A new
 * session (the panel opening again) gives a failed box one more chance. Weak keys: a disposed
 * box leaves no entry.
 */
final class QsLitRims {
    private static final long LIT = -1L;
    private final Map<Object, Long> boxes = new WeakHashMap<>();

    /** Whether this box's outline may be attenuated (its optics drew). */
    synchronized boolean lit(Object box) {
        Long s = box == null ? null : boxes.get(box);
        return s != null && s == LIT;
    }

    /**
     * A draw of this box's optics in panel session {@code session}. Returns true when the box's
     * outline changes (full to attenuated, or back), i.e. when it must recompose.
     */
    synchronized boolean report(Object box, boolean drawn, long session) {
        if (box == null) return false;
        Long s = boxes.get(box);
        if (drawn) {
            if (s != null && (s == LIT || s == session)) return false;
            boxes.put(box, LIT);
            return true;
        }
        boxes.put(box, session);
        return s != null && s == LIT;
    }

    /** The optics stopped for the process: every outline is full again. */
    synchronized void clear() { boxes.clear(); }

    synchronized int litCount() {
        int n = 0;
        for (Long s : boxes.values()) if (s == LIT) n++;
        return n;
    }
}

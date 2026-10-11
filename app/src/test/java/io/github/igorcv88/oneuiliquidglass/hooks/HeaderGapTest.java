package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HeaderGapTest {
    /** A view tree reduced to parent links, built like AOSP's big template (header over a margin-offset column). */
    private static final class Tree {
        final Map<String, String> parents = new HashMap<>();
        Tree add(String child, String parent) { parents.put(child, parent); return this; }
        UnaryOperator<String> parent() { return parents::get; }
    }
    private static Tree bigBase() {
        return new Tree()
                .add("header", "root").add("app_name", "top_line").add("top_line", "header")
                .add("margin_target", "root").add("main_column", "margin_target").add("line1", "main_column")
                .add("title", "line1").add("text", "main_column").add("progress", "main_column");
    }
    private static String apply(boolean enabled, int extra, boolean shared, Boolean headsUp) {
        return HeaderGap.skipReason(enabled, extra, shared, headsUp, true, true, true, false);
    }

    @Test public void expandedChildTakesFourDp() {
        assertNull(apply(true, HeaderGap.px(HeaderGap.DEFAULT_DP, 3f), false, false));
        // S938B's default density is 3.0 at FHD+ and 3.5 at WQHD+.
        assertEquals(12, HeaderGap.px(4f, 3f));
        assertEquals(14, HeaderGap.px(4f, 3.5f));
        assertEquals(HeaderGap.px(HeaderGap.MAX_DP, 3f), HeaderGap.px(20f, 3f));
        assertEquals(0, HeaderGap.px(0f, 3f));
        assertEquals(0, HeaderGap.px(Float.NaN, 3f));
    }

    @Test public void contentBlockBelowHeaderIsTheTarget() {
        Tree t = bigBase();
        // The whole block under the header moves, so title, text, progress and actions keep their spacing.
        assertEquals("margin_target", HeaderGap.target("root", "header", "title", t.parent()));
        assertTrue(HeaderGap.descends("progress", "margin_target", t.parent()));
    }

    @Test public void verticalLinearParentAlsoResolves() {
        Tree t = new Tree().add("header", "column").add("content", "column").add("title", "content").add("column", "root");
        assertEquals("content", HeaderGap.target("root", "header", "title", t.parent()));
        assertTrue(HeaderGap.shiftable(HeaderGap.PARENT_VERTICAL_LINEAR, -1, true));
        assertFalse(HeaderGap.shiftable(HeaderGap.PARENT_VERTICAL_LINEAR, -1, false));
    }

    @Test public void collapsedChildIsNeverTouched() {
        // The contracted template has no separate header: title and app name share the top line.
        Tree collapsed = new Tree().add("top_line", "root").add("title", "top_line").add("app_name", "top_line");
        assertNull(HeaderGap.target("root", null, "title", collapsed.parent()));
        // An expanded child that is the same instance as the contracted one keeps the native spacing.
        assertEquals("shared", apply(true, 12, true, false));
    }

    @Test public void headsUpRowsKeepTheirSpacing() {
        assertEquals("headsUp", apply(true, 12, false, true));
        // Unknown heads-up state fails closed.
        assertEquals("headsUpUnknown", apply(true, 12, false, null));
        // The heads-up child is its own instance; sharing it with the expanded child is skipped too.
        assertEquals("shared", apply(true, 12, true, null));
    }

    @Test public void probeModeAndZeroGapChangeNothing() {
        assertEquals("disabled", apply(false, 12, false, false));
        assertEquals("zero", apply(true, 0, false, false));
    }

    @Test public void missingOrUnsupportedViewsAreSafe() {
        Tree t = bigBase();
        assertNull(HeaderGap.target("root", null, "title", t.parent()));
        assertNull(HeaderGap.target("root", "header", null, t.parent()));
        assertNull(HeaderGap.target(null, "header", "title", t.parent()));
        assertNull(HeaderGap.target("root", "header", "root", t.parent()));
        // A title inside the header is part of the header row, not below it.
        t.add("header_title", "top_line");
        assertNull(HeaderGap.target("root", "header", "header_title", t.parent()));
        // A title from another tree (a stale cache entry) does not resolve.
        assertNull(HeaderGap.target("root", "header", "elsewhere", t.parent()));
        assertFalse(HeaderGap.shiftable(HeaderGap.PARENT_OTHER, -1, true));
        // Bottom or centered FrameLayout children would move the wrong way.
        assertFalse(HeaderGap.shiftable(HeaderGap.PARENT_FRAME, 0x50, false));
        assertFalse(HeaderGap.shiftable(HeaderGap.PARENT_FRAME, 0x10, false));
        assertTrue(HeaderGap.shiftable(HeaderGap.PARENT_FRAME, 0x30, false));
        assertTrue(HeaderGap.shiftable(HeaderGap.PARENT_FRAME, -1, false));
        assertEquals("structure", HeaderGap.skipReason(true, 12, false, false, false, true, true, false));
        assertEquals("noHeader", HeaderGap.skipReason(true, 12, false, false, true, false, true, false));
        assertEquals("noTitle", HeaderGap.skipReason(true, 12, false, false, true, true, false, false));
    }

    @Test public void repeatedPassesDoNotAccumulate() {
        HeaderGap.Ledger l = new HeaderGap.Ledger();
        assertEquals(138, l.update(126, true, 12));
        for (int i = 0; i < 100; i++) assertEquals(HeaderGap.NO_CHANGE, l.update(138, true, 12));
        assertEquals(126, l.baseline());
    }

    @Test public void reapplyThatResetsTheMarginGetsTheGapOnce() {
        HeaderGap.Ledger l = new HeaderGap.Ledger();
        l.update(126, true, 12);
        // A progress update reapplies the RemoteViews and writes the native margin back.
        assertEquals(138, l.update(126, true, 12));
        assertEquals(HeaderGap.NO_CHANGE, l.update(138, true, 12));
        // A new native value (density or template change) becomes the baseline.
        assertEquals(152, l.update(140, true, 12));
        assertEquals(140, l.baseline());
    }

    @Test public void reinflationStartsFromTheNativeValue() {
        HeaderGap.Ledger old = new HeaderGap.Ledger();
        old.update(126, true, 12);
        // A reinflated child is a new instance with a new ledger; it never sees the old applied value.
        HeaderGap.Ledger fresh = new HeaderGap.Ledger();
        assertEquals(138, fresh.update(126, true, 12));
        assertEquals(126, fresh.baseline());
    }

    @Test public void turningOffRestoresTheBaseline() {
        HeaderGap.Ledger l = new HeaderGap.Ledger();
        l.update(126, true, 12);
        assertEquals(126, l.update(138, false, 12));
        assertFalse(l.active());
        assertEquals(HeaderGap.NO_CHANGE, l.update(126, false, 12));
        // A tuning change from 4 dp to 2 dp moves from the baseline, not from the applied value.
        assertEquals(138, l.update(126, true, 12));
        assertEquals(132, l.update(138, true, 6));
        // Never written when skipped from the start.
        HeaderGap.Ledger skipped = new HeaderGap.Ledger();
        assertEquals(HeaderGap.NO_CHANGE, skipped.update(126, false, 12));
        assertEquals(HeaderGap.NO_CHANGE, skipped.update(126, true, 0));
    }

    @Test public void gapDependsOnDensityOnly() {
        // px() takes no font scale: the gap is a dp margin above the title block, so at any font
        // scale the title, however many lines it wraps to, starts the same distance below the header
        // and grows downward. Overlap and clipping on the device are checked in docs/physical-test.md.
        assertEquals(HeaderGap.px(4f, 3f), HeaderGap.px(4f, 3f));
        assertEquals(8, HeaderGap.px(4f, 2f));
        HeaderGap.Ledger l = new HeaderGap.Ledger();
        assertEquals(126 + 12, l.update(126, true, 12));
    }

    @Test public void noRoomTakesTheGapBack() {
        // Taller content (three-line title, large font) cut by the cap gets no gap instead of a clipped bottom.
        assertTrue(HeaderGap.noRoom(true, true, 600, 0));
        assertTrue(HeaderGap.noRoom(true, false, 600, 768));
        // A FrameLayout root that fits exactly at the cap was not cut: it keeps the gap.
        assertFalse(HeaderGap.noRoom(false, true, 768, 768));
        // A root that cannot report truncation falls back to the cap.
        assertTrue(HeaderGap.noRoom(false, false, 768, 768));
        assertFalse(HeaderGap.noRoom(false, false, 420, 768));
        assertFalse(HeaderGap.noRoom(false, false, 420, 0));
        assertEquals("noRoom", HeaderGap.skipReason(true, 12, false, false, true, true, true, true));
    }

    @Test public void movedViewsInvalidateTheCachedTarget() {
        Tree t = bigBase();
        String cached = HeaderGap.target("root", "header", "title", t.parent());
        assertEquals("margin_target", cached);
        // An update moves the header into the content block: the policy no longer picks the cached
        // target, so the hook hands its margin back and resolves again (moving the block would move
        // the header with the title).
        t.add("header", "main_column");
        assertNotEquals(cached, HeaderGap.target("root", "header", "title", t.parent()));
        // A replaced title (the old one detached) does not resolve at all.
        Tree replaced = bigBase();
        replaced.parents.remove("title");
        assertNull(HeaderGap.target("root", "header", "title", replaced.parent()));
        // A new title view in the same place resolves to the same target.
        replaced.add("title2", "line1");
        assertEquals("margin_target", HeaderGap.target("root", "header", "title2", replaced.parent()));
    }

    @Test public void largeIconMovesOnlyWithTheContentBlock() {
        // Icon aligned with the title (same top as the content block, or lower) moves with it.
        assertTrue(HeaderGap.iconMoves(126, 126));
        assertTrue(HeaderGap.iconMoves(140, 126));
        // Icon aligned with the header row stays.
        assertFalse(HeaderGap.iconMoves(36, 126));
    }

    @Test public void restoreAfterInvalidationReturnsTheNativeMargin() {
        HeaderGap.Ledger l = new HeaderGap.Ledger();
        assertEquals(138, l.update(126, true, 12));
        // restore() writes with apply=false before the entry is dropped.
        assertEquals(126, l.update(138, false, 0));
        assertFalse(l.active());
    }
}

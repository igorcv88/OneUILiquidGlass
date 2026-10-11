package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.function.UnaryOperator;

/**
 * Policy for the extra vertical gap between the header row and the title of an expanded
 * notification (debug.oulg.headergap, in dp). The content block that holds the title gets a larger
 * top margin, so the title, text, progress bar and actions move down together and the card
 * measures taller. The space below the title does not change. This class has no Android
 * dependency, so the host tests can run it; {@link HeaderGapHooks} applies it to the views.
 */
public final class HeaderGap {
    private HeaderGap() {}
    public static final float DEFAULT_DP = 4f;
    public static final float MAX_DP = 8f;
    /** A FrameLayout parent: the header overlays it and the content block is offset by its top margin. */
    public static final int PARENT_FRAME = 0;
    /** A vertical LinearLayout parent: the header comes first and the content block follows it. */
    public static final int PARENT_VERTICAL_LINEAR = 1;
    /** Any other parent; a margin there has no known direction, so nothing is changed. */
    public static final int PARENT_OTHER = 2;

    /** The extra gap in px. It is in dp only, so it does not change with font scale. */
    public static int px(float dp, float density) {
        if (!(dp > 0f) || !(density > 0f)) return 0;
        return Math.round(Math.min(dp, MAX_DP) * density);
    }

    /**
     * Why an expanded child keeps its native spacing, or null when it takes the extra gap.
     * The collapsed and heads-up children are other view instances and never get here. An expanded
     * child that is also the contracted or heads-up child is skipped. So is any row that is in the
     * heads-up state (pinned, animating away, or unpinned in the open shade), and any row whose
     * heads-up state cannot be read.
     */
    public static String skipReason(boolean enabled, int extraPx, boolean sharedWithOtherChild, Boolean headsUp,
                                    boolean structure, boolean headerShown, boolean titleShown, boolean noRoom) {
        if (!enabled) return "disabled";
        if (extraPx <= 0) return "zero";
        if (sharedWithOtherChild) return "shared";
        if (headsUp == null) return "headsUpUnknown";
        if (headsUp) return "headsUp";
        if (!structure) return "structure";
        if (!headerShown) return "noHeader";
        if (!titleShown) return "noTitle";
        if (noRoom) return "noRoom";
        return null;
    }

    /**
     * Whether a top margin on the content block makes the gap below the header larger.
     * verticalGravity is the FrameLayout child's vertical gravity: TOP (0x30), or -1 or 0 when it
     * is not set, which FrameLayout treats as top.
     */
    public static boolean shiftable(int parentKind, int verticalGravity, boolean headerBefore) {
        if (parentKind == PARENT_FRAME) return verticalGravity == -1 || verticalGravity == 0 || verticalGravity == 0x30;
        if (parentKind == PARENT_VERTICAL_LINEAR) return headerBefore;
        return false;
    }

    /**
     * The view whose top margin is raised: the highest ancestor of the title, below the expanded
     * root, that does not contain the header. Returns null when the title is missing, inside the
     * header, or not under the root.
     */
    public static <T> T target(T root, T header, T title, UnaryOperator<T> parent) {
        if (root == null || header == null || title == null || title == root) return null;
        if (!descends(header, root, parent) || !descends(title, root, parent) || descends(title, header, parent)) return null;
        T v = title;
        for (int guard = 0; guard < 64; guard++) {
            T p = parent.apply(v);
            if (p == null) return null;
            if (descends(header, p, parent)) return v;
            v = p;
        }
        return null;
    }

    /** True when node equals ancestor or sits below it. */
    public static <T> boolean descends(T node, T ancestor, UnaryOperator<T> parent) {
        T v = node;
        for (int guard = 0; v != null && guard < 64; guard++) {
            if (v == ancestor) return true;
            v = parent.apply(v);
        }
        return false;
    }

    /**
     * Whether the expanded child has no room for the extra gap. That is the case when its measure
     * reported MEASURED_STATE_TOO_SMALL (content cut by the AT_MOST cap). A root that reports that
     * state is trusted: reaching the cap exactly is not a truncation. Only a root that does not
     * report it falls back to the cap, where reaching it counts as no room. maxHeight is 0 or less
     * when the cap is unknown.
     */
    public static boolean noRoom(boolean tooSmall, boolean reportsState, int measuredHeight, int maxHeight) {
        if (tooSmall) return true;
        if (reportsState) return false;
        return maxHeight > 0 && measuredHeight >= maxHeight;
    }

    /**
     * Whether a large icon beside the content block moves with it. Both are top-gravity children
     * of the same FrameLayout, so their tops differ by their top margins. An icon that starts at or
     * below the block belongs to it; one that starts higher is aligned with the header and stays.
     */
    public static boolean iconMoves(int iconTopMargin, int targetTopMargin) {
        return iconTopMargin >= targetTopMargin;
    }

    /** Marker for {@link Ledger#update}: leave the margin as it is. */
    public static final int NO_CHANGE = Integer.MIN_VALUE;

    /**
     * One view's top margin. It records the native value (the baseline) and the value this module
     * wrote, so repeated passes never stack the gap. If the firmware writes the margin again (a
     * RemoteViews reapply on a progress update, for example), the value no longer matches ours.
     * The ledger then takes it as the new baseline and adds the gap once more.
     */
    public static final class Ledger {
        private static final int UNSET = Integer.MIN_VALUE;
        private int baseline = UNSET, applied = UNSET;

        /** The margin to write for current and the desired state, or {@link #NO_CHANGE}. */
        public int update(int current, boolean apply, int extraPx) {
            if (applied != UNSET && current == applied) {
                int next = apply ? baseline + extraPx : baseline;
                applied = apply ? next : UNSET;
                return next == current ? NO_CHANGE : next;
            }
            baseline = current;
            if (!apply || extraPx <= 0) { applied = UNSET; return NO_CHANGE; }
            applied = current + extraPx;
            return applied;
        }
        /** The native margin, valid once {@link #update} has run. */
        public int baseline() { return baseline; }
        public boolean active() { return applied != UNSET; }
    }
}

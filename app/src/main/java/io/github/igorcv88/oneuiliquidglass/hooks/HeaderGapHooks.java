package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.Resources;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;

/**
 * Adds the {@link HeaderGap} spacing to the expanded child of each NotificationContentView. The
 * work happens right before the content view measures, so the new margin is part of that same
 * measure: there is no translation and no frame drawn at the old height (see {@link #write} for
 * when one more traversal is scheduled). A pass where nothing changed costs a few field reads,
 * one map lookup and a short walk up the parents.
 * Views are resolved once per expanded-child instance. A reinflation brings a new instance,
 * which is resolved again; the old entry drops with its view.
 */
public final class HeaderGapHooks {
    private static final String CONTENT = "com.android.systemui.statusbar.notification.row.NotificationContentView";
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final Class<?> rowClass;
    private final WeakHashMap<View, Entry> entries = new WeakHashMap<>();
    private final Set<String> logged = new HashSet<>();
    private int headerId, rightIconId, topLineId;

    /**
     * Resolved views of one expanded child; {@code target == null} marks an unsupported layout. The
     * views are held weakly: they are descendants of the map's key, and a strong reference from the
     * value would keep the key, and the whole notification view, alive.
     */
    private static final class Entry {
        WeakReference<View> header, title, target, rightIcon;
        final HeaderGap.Ledger targetMargin = new HeaderGap.Ledger(), iconMargin = new HeaderGap.Ledger();
        /** Set once the gap did not fit; held until the child is reinflated, so it never oscillates. */
        boolean noRoom;
        boolean appliedThisPass;
        String layout, lastOutcome;
    }

    public HeaderGapHooks(ClassLoader loader, Class<?> rowClass, BooleanSupplier enabled) {
        this.loader = loader; this.rowClass = rowClass; this.enabled = enabled;
    }

    public void install() {
        Class<?> content;
        try { content = Class.forName(CONTENT, false, loader); }
        catch (ClassNotFoundException | LinkageError e) { Probe.log("HEADER_GAP_UNAVAILABLE", "reason=class"); return; }
        Resources system = Resources.getSystem();
        headerId = system.getIdentifier("notification_header", "id", "android");
        rightIconId = system.getIdentifier("right_icon", "id", "android");
        topLineId = system.getIdentifier("notification_top_line", "id", "android");
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(content, "onMeasure", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { before((View) p.thisObject); } catch (RuntimeException | LinkageError e) { Probe.error("HEADER_GAP_FAILED", e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { after(p); } catch (RuntimeException | ReflectiveOperationException | LinkageError e) { Probe.error("HEADER_GAP_FAILED", e); }
                }
            });
            Probe.log("HEADER_GAP", "installed=" + !hooks.isEmpty() + " headerId=" + (headerId != 0) + " rightIconId=" + (rightIconId != 0));
        } catch (RuntimeException | LinkageError e) { Probe.error("HEADER_GAP_INSTALL_FAILED", e); }
    }

    private void before(View content) {
        Object expandedObj = Reflect.read(content, "mExpandedChild");
        if (!(expandedObj instanceof ViewGroup)) return;
        View expanded = (View) expandedObj;
        Entry e = entries.get(expanded);
        if (e != null && e.target != null && !valid(e, expanded)) {
            // An update moved, replaced or dropped a resolved view: hand back the margins written to
            // the old views, then resolve this instance again.
            restore(e);
            e = null;
        }
        if (e == null) { e = resolve((ViewGroup) expanded); entries.put(expanded, e); }
        e.appliedThisPass = false;
        View header = get(e.header), title = get(e.title), target = get(e.target), icon = get(e.rightIcon);
        Tuning t = Tuning.get();
        int extra = HeaderGap.px(t.headerGap, expanded.getResources().getDisplayMetrics().density);
        boolean structure = target != null;
        boolean shared = expanded == Reflect.read(content, "mContractedChild") || expanded == Reflect.read(content, "mHeadsUpChild");
        String reason = HeaderGap.skipReason(enabled.getAsBoolean(), extra, shared, headsUp(content), structure,
                structure && HeaderGap.laidOut(header.getVisibility()), structure && shown(title), e.noRoom);
        // Nothing was resolved, so nothing was written and there is no margin to restore.
        if (target == null) { report(e, reason, extra, expanded); return; }
        boolean apply = reason == null;
        write(target, e.targetMargin, apply, extra);
        if (icon != null) write(icon, e.iconMargin, apply && icon.getVisibility() != View.GONE, extra);
        e.appliedThisPass = apply && e.targetMargin.active();
        report(e, reason, extra, expanded);
    }

    /**
     * The cached target is still the one the policy picks: the header and the title are under the
     * expanded child, the title is inside the target, and the header is outside it. This is a walk
     * up the parents of two views, with no search of the tree.
     */
    private static boolean valid(Entry e, View expanded) {
        View header = get(e.header), title = get(e.title), target = get(e.target);
        if (header == null || title == null || target == null) return false;
        if (HeaderGap.target(expanded, header, title, HeaderGapHooks::parent) != target) return false;
        View icon = get(e.rightIcon);
        return e.rightIcon == null || (icon != null && icon.getParent() == target.getParent());
    }

    private static void restore(Entry e) {
        View target = get(e.target), icon = get(e.rightIcon);
        if (target != null) write(target, e.targetMargin, false, 0);
        if (icon != null) write(icon, e.iconMargin, false, 0);
    }

    /** If the gap did not fit under the content view's height cap, take it back and measure again in the same pass. */
    private void after(XC_MethodHook.MethodHookParam p) throws ReflectiveOperationException {
        View content = (View) p.thisObject;
        Object expandedObj = Reflect.read(content, "mExpandedChild");
        if (!(expandedObj instanceof View)) return;
        View expanded = (View) expandedObj;
        Entry e = entries.get(expanded);
        if (e == null || !e.appliedThisPass) return;
        e.appliedThisPass = false;
        boolean tooSmall = (expanded.getMeasuredHeightAndState() & View.MEASURED_STATE_TOO_SMALL) != 0;
        // FrameLayout and LinearLayout flag a truncating AT_MOST measure themselves; only other roots
        // fall back to the height cap. AOSP raises the cap by the smart reply limit whenever that view
        // exists, visible or not, so the field is only used without one.
        boolean reportsState = expanded.getClass() == FrameLayout.class || expanded.getClass() == LinearLayout.class;
        Object max = Reflect.read(content, "mExpandedSmartReplyView") == null ? Reflect.read(content, "mNotificationMaxHeight") : null;
        if (!HeaderGap.noRoom(tooSmall, reportsState, expanded.getMeasuredHeight(), max instanceof Integer ? (Integer) max : 0)) return;
        e.noRoom = true;
        restore(e);
        report(e, "noRoom", 0, expanded);
        XposedBridge.invokeOriginalMethod((Member) p.method, p.thisObject, p.args);
    }

    /**
     * Writes the ledger's margin; true when the view's top margin changed. requestLayout() flags
     * the view and climbs only until it reaches an ancestor that is already flagged. On inflation,
     * reapply or a margin reset, every ancestor up to ViewRootImpl is flagged, so the climb stops
     * inside the row. The new margin then goes into the measure already running, with no second
     * traversal. Only a measure that nothing requested lets the climb reach ViewRootImpl: a parent
     * spec change, or a measure outside a traversal. That schedules one more traversal, which
     * measures the same values and keeps the expanded subtree's flags consistent: that traversal
     * clears them. Marking only the subtree with forceLayout() would leave flagged views that
     * swallow later requestLayout() calls from inside the notification when no layout follows.
     */
    private static boolean write(View v, HeaderGap.Ledger ledger, boolean apply, int extra) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return false;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int next = ledger.update(m.topMargin, apply, extra);
        if (next == HeaderGap.NO_CHANGE) return false;
        m.topMargin = next;
        v.requestLayout();
        return true;
    }

    private Entry resolve(ViewGroup expanded) {
        Entry e = new Entry();
        View header = headerId != 0 ? expanded.findViewById(headerId) : null;
        View title = expanded.findViewById(android.R.id.title);
        e.header = weak(header); e.title = weak(title);
        View target = HeaderGap.target(expanded, header, title, HeaderGapHooks::parent);
        e.layout = layoutName(expanded) + ">" + (target == null ? "none" : idName(target));
        if (Probe.trace) dumpLater(expanded, e.layout);
        if (target == null || !(target.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return e;
        ViewGroup parent = (ViewGroup) target.getParent();
        int kind = HeaderGap.PARENT_OTHER, gravity = -1;
        boolean headerBefore = false;
        if (parent instanceof FrameLayout) {
            kind = HeaderGap.PARENT_FRAME;
            gravity = verticalGravity(target);
        } else if (parent instanceof LinearLayout && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
            kind = HeaderGap.PARENT_VERTICAL_LINEAR;
            headerBefore = parent.indexOfChild(branch(parent, header)) < parent.indexOfChild(target);
        }
        if (!HeaderGap.shiftable(kind, gravity, headerBefore)) {
            log("HEADER_GAP_UNSUPPORTED", "layout=" + e.layout + " parent=" + parent.getClass().getName() + " gravity=" + gravity);
            return e;
        }
        e.target = weak(target);
        // A large icon in the same FrameLayout moves with the text only when it starts at or below the
        // title. Both are measured from the top of that FrameLayout. On One UI 9 the moved block
        // starts at 0 and the title 58 px lower, so the block's own top says nothing about where the
        // icon sits; an icon aligned with the header row stays.
        if (kind == HeaderGap.PARENT_FRAME && rightIconId != 0) {
            View icon = parent.findViewById(rightIconId);
            if (icon != null && icon.getParent() == parent && icon.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    && HeaderGap.shiftable(HeaderGap.PARENT_FRAME, verticalGravity(icon), false)
                    && HeaderGap.iconMoves(((ViewGroup.MarginLayoutParams) icon.getLayoutParams()).topMargin, contentTop(target, title))) e.rightIcon = weak(icon);
        }
        return e;
    }

    /**
     * The title's top below the top of the target's parent, from the margins and paddings on the
     * way down, so it is known before the first layout. Returns -1 when a laid-out sibling above
     * the title in a vertical LinearLayout makes the static sum wrong.
     */
    private static int contentTop(View target, View title) {
        int top = 0;
        for (View v = title; v != null; v = parent(v)) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) top += ((ViewGroup.MarginLayoutParams) lp).topMargin;
            if (v == target) return top;
            ViewGroup p = (ViewGroup) v.getParent();
            if (p == null) return -1;
            top += p.getPaddingTop();
            if (p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL) {
                for (int i = 0, n = p.indexOfChild(v); i < n; i++) if (HeaderGap.laidOut(p.getChildAt(i).getVisibility())) return -1;
            }
        }
        return -1;
    }

    /** Vertical gravity of a FrameLayout child, or -1 when it is not set. */
    private static int verticalGravity(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return -1;
        int g = ((FrameLayout.LayoutParams) lp).gravity;
        return g == -1 ? -1 : g & Gravity.VERTICAL_GRAVITY_MASK;
    }

    private static WeakReference<View> weak(View v) { return v == null ? null : new WeakReference<>(v); }
    private static View get(WeakReference<View> ref) { return ref == null ? null : ref.get(); }

    private Boolean headsUp(View content) {
        for (ViewParent p = content.getParent(); p != null; p = p.getParent()) {
            if (rowClass != null && rowClass.isInstance(p)) {
                Boolean state = Reflect.bool(p, "isHeadsUpState", "mIsHeadsUp");
                if (state == null) return null;
                if (state) return true;
                Boolean away = Reflect.bool(p, "isHeadsUpAnimatingAway", "mHeadsUpAnimatingAway");
                Boolean pinned = Reflect.bool(p, "isPinned", "mIsPinned");
                return Boolean.TRUE.equals(away) || Boolean.TRUE.equals(pinned);
            }
        }
        return null;
    }

    /**
     * The title takes layout space and has text. INVISIBLE counts: while the expanded child is not
     * the one on screen, SystemUI's transform hides its title with INVISIBLE, and going back to
     * VISIBLE does not request a layout. Requiring VISIBLE turned the gap off on collapse and never
     * on again (device log, 2026-10-11).
     */
    private static boolean shown(View title) {
        return HeaderGap.laidOut(title.getVisibility()) && (!(title instanceof TextView) || ((TextView) title).length() > 0);
    }

    private static View parent(View v) {
        ViewParent p = v.getParent();
        return p instanceof View ? (View) p : null;
    }

    /** The child of ancestor that contains node. */
    private static View branch(ViewGroup ancestor, View node) {
        View v = node;
        while (v != null && v.getParent() != ancestor) v = parent(v);
        return v;
    }

    /**
     * Logs when an expanded child's outcome changes, and only once per layout and outcome, so a
     * progress notification updating every second does not flood the log.
     */
    private void report(Entry e, String reason, int extra, View expanded) {
        String outcome = reason == null ? "applied" : reason;
        if (outcome.equals(e.lastOutcome)) return;
        e.lastOutcome = outcome;
        if (!Probe.trace && !logged.add(e.layout + "|" + outcome)) return;
        View header = get(e.header), title = get(e.title), target = get(e.target);
        log("HEADER_GAP_PASS", "layout=" + e.layout + " outcome=" + outcome + " extraPx=" + extra
                + " header=" + (header == null ? "none" : header.getClass().getName())
                + " title=" + (title == null ? "none" : title.getClass().getName())
                + " parent=" + (target == null || target.getParent() == null ? "none" : target.getParent().getClass().getSimpleName())
                + " baselinePx=" + (target == null ? -1 : e.targetMargin.baseline())
                + " rightIcon=" + (get(e.rightIcon) != null));
        if (Probe.trace && header != null && title != null) {
            View line = topLineId != 0 ? header.findViewById(topLineId) : null;
            gapLater(expanded, line != null ? line : header, title, e.layout, outcome);
        }
    }

    /**
     * debug.oulg.trace=1: after the next layout, the gap between the bottom of the header's text
     * line (notification_top_line) and the top of the title, both in window coordinates. Compare
     * headergap=0 with headergap=4. The header view itself spans the app icon (180 px on One UI 9),
     * so its own bottom sits below the title and gave a negative gap.
     */
    private static void gapLater(View expanded, View header, View title, String layout, String outcome) {
        expanded.post(() -> {
            int[] h = new int[2], t = new int[2];
            header.getLocationInWindow(h);
            title.getLocationInWindow(t);
            int headerBottom = h[1] + header.getHeight();
            Probe.log("HEADER_GAP_MEASURE", "layout=" + layout + " outcome=" + outcome + " line=" + idName(header) + " lineBottom=" + headerBottom
                    + " titleTop=" + t[1] + " gapPx=" + (t[1] - headerBottom) + " titleLines="
                    + (title instanceof TextView ? ((TextView) title).getLineCount() : -1) + " expandedH=" + expanded.getHeight());
        });
    }

    /**
     * debug.oulg.trace=1: the expanded child's hierarchy, once per layout, after its first layout:
     * class, id, visibility, top, height, top margin and top padding of each view. It shows what a
     * firmware names its header and title when the resolution above finds nothing.
     */
    private void dumpLater(View expanded, String layout) {
        if (!logged.add("dump|" + layout)) return;
        expanded.post(() -> {
            StringBuilder out = new StringBuilder();
            dump(expanded, 0, out);
            for (String line : out.toString().split("\n")) Probe.log("HEADER_GAP_TREE", "layout=" + layout + " " + line);
        });
    }

    private static void dump(View v, int depth, StringBuilder out) {
        if (depth > 12) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        out.append("d=").append(depth).append(" class=").append(v.getClass().getName()).append(" id=").append(idName(v))
                .append(" vis=").append(v.getVisibility()).append(" top=").append(v.getTop()).append(" h=").append(v.getHeight())
                .append(" mt=").append(lp instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) lp).topMargin : -1)
                .append(" pt=").append(v.getPaddingTop()).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, out);
        }
    }

    private void log(String event, String detail) {
        if (Probe.trace || logged.size() < 64) Probe.log(event, detail);
    }

    private static String layoutName(View root) {
        String id = idName(root);
        return root.getClass().getSimpleName() + (id.isEmpty() ? "" : "#" + id);
    }

    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        try { return v.getResources().getResourceEntryName(id); }
        catch (Resources.NotFoundException e) { return Integer.toHexString(id); }
    }
}

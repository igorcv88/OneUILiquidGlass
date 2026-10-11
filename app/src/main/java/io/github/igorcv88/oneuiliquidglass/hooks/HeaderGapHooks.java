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
 * pass: there is no extra layout pass, no translation, and no frame drawn at the old height. A
 * pass where nothing changed costs a few field reads and one map lookup.
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
    private int headerId, rightIconId;

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
        if (e == null) { e = resolve((ViewGroup) expanded); entries.put(expanded, e); }
        e.appliedThisPass = false;
        View header = get(e.header), title = get(e.title), target = get(e.target), icon = get(e.rightIcon);
        Tuning t = Tuning.get();
        int extra = HeaderGap.px(t.headerGap, expanded.getResources().getDisplayMetrics().density);
        // A RemoteViews reapply keeps the instance but may move or drop views: check the chain on every pass.
        boolean structure = target != null && header != null && title != null
                && HeaderGap.descends(title, target, HeaderGapHooks::parent) && HeaderGap.descends(target, expanded, HeaderGapHooks::parent)
                && HeaderGap.descends(header, expanded, HeaderGapHooks::parent);
        boolean shared = expanded == Reflect.read(content, "mContractedChild") || expanded == Reflect.read(content, "mHeadsUpChild");
        String reason = HeaderGap.skipReason(enabled.getAsBoolean(), extra, shared, headsUp(content), structure,
                structure && header.getVisibility() == View.VISIBLE, structure && shown(title), e.noRoom);
        // Nothing was resolved, so nothing was written and there is no margin to restore.
        if (target == null) { report(e, reason, extra); return; }
        boolean apply = reason == null;
        write(target, e.targetMargin, apply, extra);
        if (icon != null) write(icon, e.iconMargin, apply && icon.getVisibility() != View.GONE, extra);
        e.appliedThisPass = apply && e.targetMargin.active();
        report(e, reason, extra);
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
        // Smart replies raise the cap above mNotificationMaxHeight, so the field only counts without them.
        Object max = Reflect.read(content, "mExpandedSmartReplyView") == null ? Reflect.read(content, "mNotificationMaxHeight") : null;
        if (!HeaderGap.noRoom(tooSmall, expanded.getMeasuredHeight(), max instanceof Integer ? (Integer) max : 0)) return;
        e.noRoom = true;
        View target = get(e.target), icon = get(e.rightIcon);
        if (target != null) write(target, e.targetMargin, false, 0);
        if (icon != null) write(icon, e.iconMargin, false, 0);
        report(e, "noRoom", 0);
        XposedBridge.invokeOriginalMethod((Member) p.method, p.thisObject, p.args);
    }

    /** Writes the ledger's margin; true when the view's top margin changed. */
    private static boolean write(View v, HeaderGap.Ledger ledger, boolean apply, int extra) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return false;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        int next = ledger.update(m.topMargin, apply, extra);
        if (next == HeaderGap.NO_CHANGE) return false;
        m.topMargin = next;
        // Flags the subtree so the measure that follows does not reuse a cached size.
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
        // The large icon sits beside the title in the same FrameLayout; it moves with the text so the two stay aligned.
        if (kind == HeaderGap.PARENT_FRAME && rightIconId != 0) {
            View icon = parent.findViewById(rightIconId);
            if (icon != null && icon.getParent() == parent && icon.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    && HeaderGap.shiftable(HeaderGap.PARENT_FRAME, verticalGravity(icon), false)) e.rightIcon = weak(icon);
        }
        return e;
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

    private static boolean shown(View title) {
        return title.getVisibility() == View.VISIBLE && (!(title instanceof TextView) || ((TextView) title).length() > 0);
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
    private void report(Entry e, String reason, int extra) {
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

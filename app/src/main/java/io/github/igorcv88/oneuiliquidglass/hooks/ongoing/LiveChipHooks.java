package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/**
 * L1: compact live update chip at the top of the unlocked screen (OngoingChipAdapter). Samsung's
 * onBindViewHolder calls setChipBg, which builds a new GradientDrawable, sets it as background
 * and tints it: that writer is what removed any earlier glass. Each setChipBg inside a bind is
 * attributed to the data bound at that position; at the end of the bind every chip view of the
 * holder (written now or in an earlier bind, i.e. recycled) is classified against <i>this</i>
 * bind's data, so a holder reused for media goes back to native. A setChipBg outside a bind
 * (recolour) reasserts only a chip already classified, after re-reading its data's flags.
 */
final class LiveChipHooks {
    static final String ADAPTER = "com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip.OngoingChipAdapter";
    static final Predicate<Object> RECHECK = d -> LiveEligibility.ongoing(d).ok();

    /** The data bound at a position (DZIF: OngoingActivityDataHelper.getDataByIndex). */
    interface Locator { Object at(Object adapter, int position); }

    private static final class Frame {
        final Object adapter, holder, data;
        final int position;
        final List<View> written = new ArrayList<>();
        Frame(Object adapter, Object holder, int position, Object data) {
            this.adapter = adapter; this.holder = holder; this.position = position; this.data = data;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final LiveGlass glass;
    private Locator locator;

    LiveChipHooks(LiveGlass glass) {
        this.glass = glass;
        DataLocator lists = new DataLocator(LiveEligibility.DATA);
        locator = lists::at;
    }
    /** The helper Samsung's adapter itself uses; without it only an unambiguous data list is accepted. */
    void setLocator(Locator l) { locator = l; }

    /** Before OngoingChipAdapter.onBindViewHolder(holder, position). */
    void bindStart(Object adapter, Object holder, int position) {
        // A bind whose end was never seen (it threw before our after callback) is dropped.
        while (frames.size() >= 4) frames.removeLast();
        frames.push(new Frame(adapter, holder, position, locator.at(adapter, position)));
    }

    /** After OngoingChipAdapter.setChipBg(ViewGroup, ColorStateList). */
    void chipBackground(Object adapter, View chip) {
        if (chip == null || glass.writing()) return;
        Frame f = frames.peek();
        if (f != null && f.adapter == adapter) { if (!f.written.contains(chip)) f.written.add(chip); return; }
        if (glass.tracked(chip)) glass.rewrite(chip);
    }

    /** After onBindViewHolder; {@code threw}: the original failed, nothing is classified. */
    void bindEnd(Object adapter, Object holder, boolean threw) {
        Frame f = null;
        while (!frames.isEmpty()) {
            Frame top = frames.pop();
            if (top.adapter == adapter && top.holder == holder) { f = top; break; }
        }
        if (f == null) return;
        // The data the holder itself keeps, when it keeps one, is the item actually shown: it
        // wins over the helper index (whose list may hold items the chip adapter does not show,
        // such as media, shifting the positions).
        Object held = heldData(holder);
        Object data = held != null ? held : f.data;
        LiveEligibility.Verdict verdict = LiveEligibility.ongoing(data);
        // Without it, the data must still be the one at the bound position when the bind ends: a
        // list that changed during the bind gives no trustworthy owner.
        if (threw || (held == null && locator.at(adapter, f.position) != f.data)) verdict = LiveEligibility.Verdict.NO_DATA;
        Set<View> hosts = new LinkedHashSet<>(f.written);
        View item = itemView(holder);
        if (item != null) for (View h : glass.hosts(LiveSurface.CHIP)) if (within(h, item)) hosts.add(h);
        for (View h : hosts) glass.offer(h, LiveSurface.CHIP, data, verdict, RECHECK);
    }

    private static final Object NONE = new Object();
    private final java.util.Map<Class<?>, Object> holderFields = new java.util.WeakHashMap<>();
    /**
     * The OngoingActivityData the holder keeps in its single field of that type (resolved once per
     * holder class), or its itemView's tag; null when it keeps none or more than one.
     */
    Object heldData(Object holder) {
        if (holder == null) return null;
        Object member;
        synchronized (holderFields) {
            member = holderFields.get(holder.getClass());
            if (member == null) { member = dataField(holder.getClass()); holderFields.put(holder.getClass(), member); }
        }
        if (member instanceof java.lang.reflect.Field) {
            try {
                Object v = ((java.lang.reflect.Field) member).get(holder);
                if (v != null && LiveEligibility.DATA.equals(v.getClass().getName())) return v;
            } catch (IllegalAccessException | RuntimeException ignored) { }
        }
        View item = itemView(holder);
        Object tag = item == null ? null : item.getTag();
        return tag != null && LiveEligibility.DATA.equals(tag.getClass().getName()) ? tag : null;
    }
    private static Object dataField(Class<?> type) {
        java.lang.reflect.Field found = null;
        for (Class<?> c = type; c != null && !DataLocator.framework(c); c = c.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try { fields = c.getDeclaredFields(); } catch (LinkageError | RuntimeException e) { continue; }
            for (java.lang.reflect.Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !LiveEligibility.DATA.equals(f.getType().getName())) continue;
                if (found != null) return NONE;
                found = f;
            }
        }
        if (found == null) return NONE;
        // Access can be refused (hidden-API policy, module/loader rules): then the holder is not
        // read and the helper path stays in charge, as without a data field.
        try { found.setAccessible(true); }
        catch (RuntimeException | LinkageError e) { return NONE; }
        return found;
    }

    static View itemView(Object holder) {
        Object v = Reflect.read(holder, "itemView");
        return v instanceof View ? (View) v : null;
    }
    /** Whether {@code v} is {@code root} or inside it. */
    static boolean within(View v, View root) {
        Object p = v;
        for (int i = 0; i < 32 && p instanceof View; i++) {
            if (p == root) return true;
            p = ((View) p).getParent();
        }
        return false;
    }
    static boolean isGroup(Object o) { return o instanceof ViewGroup; }
}

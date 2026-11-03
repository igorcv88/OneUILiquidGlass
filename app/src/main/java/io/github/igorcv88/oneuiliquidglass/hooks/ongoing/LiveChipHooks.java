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

    private static final class Frame {
        final Object adapter, holder, data;
        final List<View> written = new ArrayList<>();
        Frame(Object adapter, Object holder, Object data) { this.adapter = adapter; this.holder = holder; this.data = data; }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final LiveGlass glass;
    private final DataLocator locator = new DataLocator(LiveEligibility.DATA);

    LiveChipHooks(LiveGlass glass) { this.glass = glass; }

    /** Before OngoingChipAdapter.onBindViewHolder(holder, position). */
    void bindStart(Object adapter, Object holder, int position) {
        // A bind whose end was never seen (it threw before our after callback) is dropped.
        while (frames.size() >= 4) frames.removeLast();
        frames.push(new Frame(adapter, holder, locator.at(adapter, position)));
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
        LiveEligibility.Verdict verdict = LiveEligibility.ongoing(f.data);
        if (threw) verdict = LiveEligibility.Verdict.NO_DATA;
        Set<View> hosts = new LinkedHashSet<>(f.written);
        View item = itemView(holder);
        if (item != null) for (View h : glass.hosts(LiveSurface.CHIP)) if (within(h, item)) hosts.add(h);
        for (View h : hosts) glass.offer(h, LiveSurface.CHIP, f.data, verdict, RECHECK);
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

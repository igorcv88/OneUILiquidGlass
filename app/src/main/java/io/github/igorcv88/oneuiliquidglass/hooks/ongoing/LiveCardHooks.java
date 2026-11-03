package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * L2: expanded card of the top live update (OngoingCardAdapter + CardStackView). getView/bindView
 * classify the card root with Samsung's own isMediaView(View) and the data at that position; the
 * stack's writers (changeCardBg rebuilds a GradientDrawable, setBlurBG re-installs a Samsung blur,
 * updateItemBg changes every item's background) are followed on the classified card only. The
 * app's RemoteViews content is never touched: only a view whose background Samsung writes (the
 * root, or a target of changeCardBg/setBlurBG inside it) carries the glass.
 */
final class LiveCardHooks {
    static final String ADAPTER = "com.android.systemui.statusbar.phone.ongoingactivity.OngoingCardAdapter";
    static final String STACK = "com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView";

    private static final class Classification {
        final WeakReference<Object> data;
        final LiveEligibility.Verdict verdict;
        Classification(Object data, LiveEligibility.Verdict verdict) { this.data = new WeakReference<>(data); this.verdict = verdict; }
    }
    private final WeakHashMap<View, Classification> roots = new WeakHashMap<>();
    private final LiveGlass glass;
    private final DataLocator locator = new DataLocator(LiveEligibility.DATA);
    /** OngoingCardAdapter.isMediaView(View), resolved at install; null: no native verdict, all native. */
    private Method isMediaView;
    /** DZIF's data helper, used when the adapter exposes no getItem(int) nor data list. */
    private OngoingDataSource helper;
    void setHelper(OngoingDataSource h) { helper = h; }

    LiveCardHooks(LiveGlass glass) { this.glass = glass; }
    void setIsMediaView(Method m) { isMediaView = m; }

    /** After getView(position, convert, parent) (result) and bindView(view, position). */
    void cardBound(Object adapter, View root, int position) {
        if (root == null) return;
        Object data = locator.at(adapter, position);
        if (data == null && helper != null) data = helper.at(adapter, position);
        LiveEligibility.Verdict verdict = LiveEligibility.card(data, nativeMedia(adapter, root));
        roots.put(root, new Classification(data, verdict));
        if (root.getBackground() != null || glass.tracked(root)) glass.offer(root, LiveSurface.CARD, data, verdict, LiveChipHooks.RECHECK);
        for (View h : glass.hosts(LiveSurface.CARD))
            if (h != root && LiveChipHooks.within(h, root)) glass.offer(h, LiveSurface.CARD, data, verdict, LiveChipHooks.RECHECK);
    }

    /** After changeCardBg(View, int), setBlurBG(View, int) and initNormalNotificationView(View). */
    void cardWriter(View target) {
        if (target == null || glass.writing()) return;
        Classification c = classify(target);
        if (c == null) { if (glass.tracked(target)) glass.forget(target, "unclassified"); return; }
        Object data = c.data.get();
        // The native media verdict is the bind's; the data's own flags are read again now (they can
        // flip to media or automation in place before the next bind).
        LiveEligibility.Verdict verdict = data == null ? LiveEligibility.Verdict.NO_DATA
                : c.verdict.ok() ? LiveEligibility.ongoing(data) : c.verdict;
        glass.offer(target, LiveSurface.CARD, data, verdict, LiveChipHooks.RECHECK);
    }

    /** After CardStackView.updateItemBg(): every classified card of that stack, nothing else. */
    void stackUpdated(View stack) {
        if (stack == null || glass.writing()) return;
        for (View h : glass.hosts(LiveSurface.CARD)) if (LiveChipHooks.within(h, stack)) glass.rewrite(h);
    }

    Boolean nativeMedia(Object adapter, View root) {
        if (isMediaView == null || adapter == null) return null;
        try {
            Object r = isMediaView.invoke(adapter, root);
            return r instanceof Boolean ? (Boolean) r : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    private Classification classify(View target) {
        Object p = target;
        for (int i = 0; i < 8 && p instanceof View; i++) {
            Classification c = roots.get(p);
            if (c != null) return c;
            p = ((View) p).getParent();
        }
        return null;
    }
}

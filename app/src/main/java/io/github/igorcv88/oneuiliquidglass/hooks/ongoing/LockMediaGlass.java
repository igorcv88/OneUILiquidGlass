package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lens on the lockscreen Now Bar media capsule (NowBarMediaCardView), compact layout only.
 *
 * <p>Evidence (LockMediaProbe, DZIF, 04/11/2026): removing the backgrounds of
 * nowbar_cardview_background and stack_background_cardview (#163) changed nothing on screen;
 * hiding the decoration CardView stack_background_cardview (#164 mode 5) removed the opaque fill,
 * and hiding nowbar_card_border_stroke_view as well (mode 7) also removed Samsung's border. Those
 * two views hold no art, text, controls or WaveView. Removing only the foreground (mode 4) left a
 * black fill and is not used.</p>
 *
 * <p>Applied: a {@link GlassLayer} inside nowbar_cardview_background (the CardView that clips to
 * the capsule outline and sits under the content), offered to {@link LiveGlass} with the CardView
 * as shape source, as the ongoing Now Bar card does; its background (aod.m96) and colour setters
 * are left alone (setCardBackgroundColor casts a background it does not own). The fill and the
 * stroke are made INVISIBLE (layout slot kept) exactly while the lens is drawn, from the glass's
 * own shown/sync callbacks, so the native capsule is never removed without a lens and the doze
 * policy (native on AOD) keeps working. Entry is the card's constructor: the layer is added on
 * attach and synced from the card's pre-draw, before its first visible frame, not from the
 * one-second diagnostic scan.</p>
 *
 * <p>Expanded stays native without tearing the lens down (OULG_LOCK165_CLEAN, DZIF: every
 * expand/collapse removed the layer and built a new one, whose first draw was EMPTY 0x0): the
 * same layer and LiveGlass state are kept, the layer is parked INVISIBLE (its accumulated alpha
 * is 0, so its blur region is not drawn) and fill and stroke get their visibility back. Collapse
 * follows Samsung's setExpandedCardState(false) at once. A full-size blur region during this
 * animation left a trailing oversized bubble on DZIF, so the layer remains parked until the
 * card approaches its last measured compact height. During collapse the native fill and stroke
 * are hidden if glass is available, avoiding an opaque intermediate frame without making a
 * full-height blur region. A card that is not attached is never synced. The layer is removed only
 * for gate off, detach, a missing container or an error. Per frame: geometry and reference
 * checks on cached views; the views are resolved by id once per attach.</p>
 */
final class LockMediaGlass {
    static final String MEDIA = NowBarHooks.PRESENTATION + "NowBarMediaCardView";
    // Resource entry names on DZIF; not final only so tests can use framework ids Robolectric knows.
    static String CONTAINER = "nowbar_cardview_background";
    static String FILL = "stack_background_cardview";
    static String STROKE = "nowbar_card_border_stroke_view";

    private final LiveGlass glass;
    private final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    /** Last setExpandedCardState of each Now Bar stack. */
    private final WeakHashMap<View, Boolean> expandedStacks = new WeakHashMap<>();

    LockMediaGlass(LiveGlass glass) { this.glass = glass; }

    static boolean media(Object view) { return view != null && MEDIA.equals(view.getClass().getName()); }

    /** From the card's constructor (or any later writer): idempotent. */
    void watch(View card) {
        if (!media(card) || cards.containsKey(card)) return;
        Card c = new Card(card);
        cards.put(card, c);
        card.addOnAttachStateChangeListener(c);
        if (card.isAttachedToWindow()) c.onViewAttachedToWindow(card);
    }

    /** After NowBarCardStackView.setExpandedCardState(expanded). */
    void expandedState(View stack, boolean expanded) {
        if (stack == null) return;
        expandedStacks.put(stack, expanded);
        for (Card c : new ArrayList<>(cards.values())) if (c.stack.get() == stack) c.sync();
    }

    /** Gate or config changed. */
    void refreshAll() { for (Card c : new ArrayList<>(cards.values())) c.sync(); }

    int tracked() { return cards.size(); }

    /** Per card. Only weak references: the value of a weak map keyed by the card. */
    final class Card implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> card;
        WeakReference<View> stack = new WeakReference<>(null);
        WeakReference<View> container = new WeakReference<>(null);
        WeakReference<GlassLayer> layer = new WeakReference<>(null);
        final Hide fill = new Hide(), stroke = new Hide();
        ViewTreeObserver observer;
        String lastNative;
        boolean lastShown;
        /** Measured in the compact state, never guessed from the expanded CardView. */
        int compactHeight;

        Card(View card) { this.card = new WeakReference<>(card); }

        @Override public void onViewAttachedToWindow(View v) {
            compactHeight = 0;
            stack = new WeakReference<>(findStack(v));
            container = new WeakReference<>(NativeIds.find(v, CONTAINER));
            fill.target = new WeakReference<>(NativeIds.find(v, FILL));
            stroke.target = new WeakReference<>(NativeIds.find(v, STROKE));
            observer = v.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
            // Before the first traversal: the layer is laid out with the card in its first frame.
            sync();
        }

        @Override public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            release("detached");
        }

        @Override public boolean onPreDraw() {
            try { sync(); }
            catch (RuntimeException | LinkageError e) { release("error"); Probe.error("LOCK_MEDIA_GLASS_FAILED", e); }
            return true;
        }

        void sync() {
            View k = card.get();
            // A stale instance (detached, already released) does no work and gets no layer.
            if (k == null || !k.isAttachedToWindow()) return;
            if (!glass.allows(LiveSurface.NOWBAR)) { release("gate"); return; }
            View c = container.get();
            if (c == null || !c.isAttachedToWindow()) {
                c = NativeIds.find(k, CONTAINER);
                container = new WeakReference<>(c);
            }
            if (!(c instanceof ViewGroup) || !NativeShape.cardLike(c)) { release("noContainer"); return; }
            GlassLayer l = layer(this, (ViewGroup) c);
            boolean compact = compact(k);
            boolean collapsing = collapsing(k) && !compact;
            park(l, !compact);
            if (!glass.tracked(l)) offer(k, c, l);
            // During collapse hide Samsung's opaque shell while the (still parked) lens awaits
            // compact bounds. On expanded/unknown tall cards leave the shell native.
            if (collapsing) {
                LiveGlassDrawable g = glass.glassOf(l);
                decorations(g != null && g.showsGlass());
            } else if (!compact) decorations(false);
        }

        /** The expensive region is drawn only when its geometry is capsule-sized. */
        private void park(GlassLayer l, boolean parked) {
            int want = parked ? View.INVISIBLE : View.VISIBLE;
            if (l.getVisibility() == want) return;
            l.setVisibility(want);
            Probe.log("LOCK_MEDIA_GLASS", "state=" + (parked ? "parked" : "unparked") + " card=" + id(card.get()));
        }

        private void offer(View k, View c, GlassLayer l) {
            final WeakReference<Card> self = new WeakReference<>(this);
            LiveGlass.Options options = new LiveGlass.Options();
            options.allowEmptyNative = true;
            options.shapeSource = c;
            options.onShown = (v, shown) -> { Card me = self.get(); if (me != null) me.decorations(shown); };
            options.onSync = options.onShown;
            LiveGlass.Outcome o = glass.offer(l, LiveSurface.NOWBAR, k, LiveEligibility.Verdict.OK, owner -> {
                Card me = self.get();
                View card = me == null ? null : me.card.get();
                // Expanded is parked, not ineligible: LiveGlass.refresh must not tear down the
                // drawable that will be reused when the compact card returns.
                return card != null && card == owner && card.isAttachedToWindow();
            }, options);
            lastNative = null;
            Probe.log("LOCK_MEDIA_GLASS", "state=offered outcome=" + o + " card=" + id(k) + " container=" + id(c));
            if (!glass.applied(l)) decorations(false);
        }

        /** Fill and stroke hidden exactly while the lens is drawn. */
        void decorations(boolean shown) {
            View k = card.get();
            boolean on = shown && k != null && (compact(k) || collapsing(k));
            boolean changed = fill.sync(on) | stroke.sync(on);
            if (changed || on != lastShown) {
                lastShown = on;
                Probe.log("LOCK_MEDIA_GLASS", "state=" + (on ? "lens" : "native") + " fillHidden=" + fill.hidden
                        + " strokeHidden=" + stroke.hidden + " card=" + id(k));
            }
        }

        void release(String reason) {
            GlassLayer l = layer.get();
            boolean had = l != null || fill.hidden || stroke.hidden;
            if (l != null) {
                glass.forget(l, reason);
                if (l.getParent() instanceof ViewGroup) ((ViewGroup) l.getParent()).removeView(l);
                layer = new WeakReference<>(null);
            }
            fill.sync(false);
            stroke.sync(false);
            lastShown = false;
            if (had || !reason.equals(lastNative)) {
                lastNative = reason;
                Probe.log("LOCK_MEDIA_GLASS", "state=native reason=" + reason + " card=" + id(card.get()));
            }
        }

        private boolean collapsing(View k) {
            return k != null && Boolean.FALSE.equals(expandedStacks.get(stack.get()));
        }

        private boolean compact(View k) {
            if (k == null) return false;
            int w = k.getWidth(), h = k.getHeight();
            boolean close = compactEnough(w, h, compactHeight);
            // Save a real capsule size, including on the first attach. Never learn from the
            // expanded animation, or the next collapse would redraw its oversized blur region.
            if (close && w > 0 && h > 0 && (compactHeight == 0 || h < compactHeight)) compactHeight = h;
            return close;
        }
    }

    /** INVISIBLE while held; the native visibility comes back only if ours is still in place. */
    static final class Hide {
        WeakReference<View> target = new WeakReference<>(null);
        private WeakReference<View> held = new WeakReference<>(null);
        private int nativeVisibility = View.VISIBLE;
        boolean hidden;

        /** True when something was written. */
        boolean sync(boolean on) {
            View v = target.get();
            View h = held.get();
            if (hidden && (!on || h != v)) {
                if (h != null && h.getVisibility() == View.INVISIBLE) h.setVisibility(nativeVisibility);
                hidden = false;
                held = new WeakReference<>(null);
                if (!on || v == null) return true;
            }
            if (!on || v == null) return false;
            if (!hidden) {
                // A view Samsung itself hides (GONE/INVISIBLE) is left as it is.
                if (v.getVisibility() != View.VISIBLE) return false;
                nativeVisibility = View.VISIBLE;
                held = new WeakReference<>(v);
                hidden = true;
                v.setVisibility(View.INVISIBLE);
                return true;
            }
            if (v.getVisibility() == View.VISIBLE) {
                // Samsung showed it again while the lens is on: hide it again.
                v.setVisibility(View.INVISIBLE);
                return true;
            }
            return false;
        }
    }

    /** The layer inside the capsule CardView, added once and re-added if Samsung dropped it. */
    private static GlassLayer layer(Card c, ViewGroup container) {
        GlassLayer l = c.layer.get();
        if (l == null) { l = new GlassLayer(container.getContext()); c.layer = new WeakReference<>(l); }
        if (l.getParent() != container) {
            if (l.getParent() instanceof ViewGroup) ((ViewGroup) l.getParent()).removeView(l);
            container.addView(l, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return l;
    }

    /** An optical region outside this envelope would form a large trailing blur bubble. */
    static boolean compactEnough(int width, int height, int lastCompactHeight) {
        if (width <= 0 || height <= 0) return true; // first layout will measure before drawing
        int maxHeight = lastCompactHeight > 0
                ? Math.max(lastCompactHeight + 12, Math.round(lastCompactHeight * 1.14f))
                : Math.round(width * 0.34f);
        return height <= Math.min(Math.round(width * 0.42f), maxHeight);
    }

    private static View findStack(View card) {
        ViewParent p = card.getParent();
        for (int i = 0; i < 4 && p instanceof View; i++, p = p.getParent())
            if (NowBarHooks.STACK.equals(p.getClass().getName())) return (View) p;
        return null;
    }

    private static String id(View v) { return v == null ? "null" : Integer.toHexString(System.identityHashCode(v)); }

    // Tests.
    Card cardForTest(View card) { return cards.get(card); }
    Map<View, Boolean> expandedForTest() { return expandedStacks; }
}

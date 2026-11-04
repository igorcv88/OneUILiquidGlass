package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import io.github.igorcv88.oneuiliquidglass.diagnostics.LiveRenderProbe;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * L2: expanded card of the top live update (OngoingCardAdapter + CardStackView).
 *
 * <p>DZIF's real tree (window OngoingCard, 02/11/2026), all at the same frame:</p>
 * <pre>
 * CardStackView
 *  └ FrameLayout (RippleDrawable)
 *     └ FrameLayout (BackgroundBlurDrawable)   Samsung's own blur region (setBlurBG)
 *        └ CardItemView (no background)        root returned by the adapter
 *           └ FrameLayout (GradientDrawable)   the opaque card fill (changeCardBg)
 * </pre>
 * Each eligible fill owns a stable material region. Samsung's ancestor region is suspended
 * while that material is enabled and restored on fallback, detach or gate changes. Geometry
 * updates never switch the card to an unconfigured native region.
 *
 * <p>Ownership: getView/bindView classify the root with the data at that position. The fill is
 * found structurally (a GradientDrawable host that covers the root,
 * never a fixed depth) or is the view changeCardBg wrote. Every writer (changeCardBg, setBlurBG,
 * updateItemBg, initNormalNotificationView) re-evaluates only classified cards it touches, with
 * the data's flags re-read.</p>
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
    /** Fill views changeCardBg wrote, by the root they belong to (found by structure otherwise). */
    private final WeakHashMap<View, WeakReference<View>> writtenFill = new WeakHashMap<>();
    /**
     * The fill chosen for each root, kept while it stays a valid fill of that root. Choosing it
     * again on every writer from live sizes made it flap during expand/collapse (sizes differ for a
     * few frames), restoring and re-wrapping it in pairs ~20 ms apart (LIVE_GLASS_RESTORE
     * reason=notFill dozens of times, DZIF log 03/11).
     */
    private final WeakHashMap<View, WeakReference<View>> fillOf = new WeakHashMap<>();
    /** Colour of each native blur region before the glass cleared it, for an exact restore. */
    private final WeakHashMap<Drawable, Integer> regionColors = new WeakHashMap<>();
    /** Only background layers on this fill's ancestor path; never RemoteViews or controls. */
    private final WeakHashMap<View, List<WeakReference<View>>> covered = new WeakHashMap<>();
    /** Exact Samsung action buttons within each classified card, not arbitrary notification UI. */
    private final WeakHashMap<View, List<WeakReference<View>>> actionButtons = new WeakHashMap<>();
    private static final class StackLease {
        final WeakReference<View> layer;
        final LiveStackFill fill = new LiveStackFill();
        StackLease(View v) { layer = new WeakReference<>(v); }
    }
    private final WeakHashMap<View, List<StackLease>> stackFills = new WeakHashMap<>();
    private final WeakHashMap<View, LiveActionFill> actionFills = new WeakHashMap<>();
    private static final class TintedRegion {
        final WeakReference<View> layer;
        final WeakReference<Drawable> drawable;
        final NativeRegionLease lease = new NativeRegionLease();
        TintedRegion(View v, Drawable d) { layer = new WeakReference<>(v); drawable = new WeakReference<>(d); }
    }
    private final WeakHashMap<View, List<TintedRegion>> tinted = new WeakHashMap<>();
    private static final class CompanionSignature {
        boolean shown;
        int regions = -1, fills = -1;
    }
    private final WeakHashMap<View, CompanionSignature> companionTrace = new WeakHashMap<>();
    private final WeakHashMap<Drawable, LiveRenderProbe> tintTrace = new WeakHashMap<>();
    private final LiveGlass glass;
    private final LiveGlass.NativeBlur nativeBlur;
    private final DataLocator locator = new DataLocator(LiveEligibility.DATA);
    /** DZIF's data helper, used when the adapter exposes no getItem(int) nor data list. */
    private OngoingDataSource helper;
    void setHelper(OngoingDataSource h) { helper = h; }

    LiveCardHooks(LiveGlass glass) { this(glass, LiveGlass.SAMSUNG); }
    LiveCardHooks(LiveGlass glass, LiveGlass.NativeBlur nativeBlur) { this.glass = glass; this.nativeBlur = nativeBlur; }

    /** After getView(position, convert, parent) (result) and bindView(view, position). */
    void cardBound(Object adapter, View root, int position) {
        if (root == null) return;
        Object data = locator.at(adapter, position);
        if (data == null && helper != null) data = helper.at(adapter, position);
        LiveEligibility.Verdict verdict = LiveEligibility.card(data);
        Classification c = new Classification(data, verdict);
        roots.put(root, c);
        evaluate(root, c);
        if (verdict.ok()) Probe.liveSurfaceLater(root, "CARD", 450);
    }

    /** After changeCardBg(View, int), setBlurBG(View, int) and initNormalNotificationView(View). */
    void cardWriter(View target, boolean fillWriter) {
        if (target == null || glass.writing()) return;
        View root = rootOf(target);
        if (root != null) {
            // The view changeCardBg rewrote inside a card is that card's fill, whatever its depth.
            if (fillWriter && target != root && target.getBackground() != null && !blurLayer(target)) writtenFill.put(root, new WeakReference<>(target));
            evaluate(root, roots.get(root));
            Probe.liveSurfaceLater(root, "CARD", 450);
            return;
        }
        // A writer on a layer above the cards (setBlurBG on the blur frame): its cards change mode.
        boolean any = false;
        for (Map.Entry<View, Classification> e : new ArrayList<>(roots.entrySet())) {
            View r = e.getKey();
            if (r != null && LiveChipHooks.within(r, target)) { evaluate(r, e.getValue()); any = true; }
        }
        if (!any && glass.tracked(target)) glass.forget(target, "unclassified");
    }
    /** Old single-argument form (tests, initNormalNotificationView). */
    void cardWriter(View target) { cardWriter(target, false); }

    /** After CardStackView.updateItemBg(): every classified card of that stack, nothing else. */
    void stackUpdated(View stack) {
        if (stack == null || glass.writing()) return;
        for (Map.Entry<View, Classification> e : new ArrayList<>(roots.entrySet()))
            if (e.getKey() != null && LiveChipHooks.within(e.getKey(), stack)) evaluate(e.getKey(), e.getValue());
    }

    /** Offers the card's fill (and a root that has its own background) with the current verdict. */
    private void evaluate(View root, Classification c) {
        if (c == null) return;
        Object data = c.data.get();
        // Recheck mutable data (especially automation) before each writer; the bind's structural
        // result is retained only when it was already ineligible.
        LiveEligibility.Verdict verdict = data == null ? LiveEligibility.Verdict.NO_DATA
                : c.verdict.ok() ? LiveEligibility.ongoing(data) : c.verdict;
        LiveGlass.Options options = new LiveGlass.Options();
        // Keep one material region through expansion, including the settled frame.
        // Samsung's structural region is suspended by the companion lease below.
        options.onShown = this::regionTint;
        options.onSync = this::regionTint;
        List<View> targets = fills(root);
        for (View t : targets) {
            glass.offer(t, LiveSurface.CARD, data, verdict, LiveChipHooks.RECHECK, options);
            // Samsung's writer may have put its region colour back: clear it again while glass shows.
            io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable g = glass.glassOf(t);
            if (g != null && g.showsGlass()) regionTint(t, true);
        }
        // Hosts of this card that are no longer its fill (recycled layout) go back to native.
        for (View h : glass.hosts(LiveSurface.CARD))
            if (h != null && !targets.contains(h) && (h == root || LiveChipHooks.within(h, root))) glass.forget(h, "notFill");
    }

    /**
     * The views drawing this card's fill: the root itself when it has a background of its own, the
     * view changeCardBg wrote, and the descendants whose GradientDrawable covers the root. Samsung's
     * blur layers and touch-feedback backgrounds are never candidates.
     */
    List<View> fills(View root) {
        List<View> out = new ArrayList<>();
        // Samsung's own writer names the fill; otherwise the fill already chosen stays while it is
        // valid; only then is the tree searched. The choice is stable across writers and animations.
        View chosen = null;
        WeakReference<View> w = writtenFill.get(root);
        View written = w == null ? null : w.get();
        if (validFill(written, root)) chosen = written;
        if (chosen == null) {
            WeakReference<View> k = fillOf.get(root);
            View kept = k == null ? null : k.get();
            if (validFill(kept, root)) chosen = kept;
        }
        if (chosen == null && root instanceof ViewGroup) {
            List<View> found = new ArrayList<>();
            collect((ViewGroup) root, root, found, 0);
            if (!found.isEmpty()) chosen = found.get(0);
        }
        if (chosen != null) {
            fillOf.put(root, new WeakReference<>(chosen));
            if (!out.contains(chosen)) out.add(chosen);
        } else if (fillCandidate(root)) out.add(root);
        return out;
    }
    private boolean validFill(View v, View root) {
        return v != null && v != root && LiveChipHooks.within(v, root) && fillCandidate(v) && gradientFill(v);
    }
    private void collect(ViewGroup g, View root, List<View> out, int level) {
        if (level > 3) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (fillCandidate(v) && gradientFill(v) && covers(v, root)) { out.add(v); continue; }
            if (v instanceof ViewGroup) collect((ViewGroup) v, root, out, level + 1);
        }
    }
    /** A GradientDrawable fill, also once wrapped by the glass. */
    private static boolean gradientFill(View v) {
        Drawable d = v.getBackground();
        if (d instanceof io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable) d = ((io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable) d).nativeDrawable();
        return NativeShape.unwrap(d) instanceof GradientDrawable;
    }
    private boolean fillCandidate(View v) {
        Drawable d = v.getBackground();
        if (d == null || blurLayer(v)) return false;
        if (d instanceof io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable) return true;
        return !NativeShape.interactive(d);
    }
    /**
     * Covers the root: match_parent in both directions (stable through animations), otherwise the
     * same laid-out frame. Live sizes are only the fallback: they differ for a few frames while the
     * card expands or collapses.
     */
    static boolean covers(View v, View root) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) return true;
        return v.getWidth() > 0 && root.getWidth() > 0
                && Math.abs(v.getWidth() - root.getWidth()) <= 2 && Math.abs(v.getHeight() - root.getHeight()) <= 2;
    }

    /**
     * While our material owns the region, suspend Samsung's competing ancestor region and clear
     * its colour overlay. Restore the saved alpha and colour on fallback or detach; never change
     * its radius, curve or shape. Unknown colour setters are left alone.
     */
    void regionTint(View host, boolean shown) {
        if (host == null) return;
        // Restore companions even after a fill was reparented or recycled.
        if (!shown) {
            restoreCompanions(host);
            companionState(host, false);
            return;
        }
        syncActions(host);
        List<StackLease> stackLeases = stackFills.computeIfAbsent(host, ignored -> new ArrayList<>());
        stackLeases.removeIf(lease -> {
            View frame = lease.layer.get();
            if (frame != null && LiveChipHooks.within(host, frame)) return false;
            lease.fill.restore();
            return true;
        });
        List<TintedRegion> prior = tinted.get(host);
        if (prior != null) prior.removeIf(r -> {
            View layer = r.layer.get();
            Drawable d = r.drawable.get();
            boolean stale = layer == null || d == null || layer.getBackground() != d || !LiveChipHooks.within(host, layer);
            if (stale) { r.lease.restore(); if (layer != null && d != null) tintRegion(layer, d, false); }
            return stale;
        });
        List<WeakReference<View>> oldFills = covered.get(host);
        if (oldFills != null) oldFills.removeIf(ref -> {
            View layer = ref.get();
            boolean stale = layer == null || !LiveChipHooks.within(host, layer);
            if (stale && layer != null) restoreCovered(layer);
            return stale;
        });
        Object p = host.getParent();
        for (int i = 0; i < 16 && p instanceof View; i++) {
            View layer = (View) p;
            if (STACK.equals(layer.getClass().getName())) break;
            Drawable d = layer.getBackground();
            if (d instanceof android.graphics.drawable.RippleDrawable && stackFrame(layer)) {
                StackLease lease = null;
                for (StackLease kept : stackLeases) if (kept.layer.get() == layer) { lease = kept; break; }
                if (lease == null) { lease = new StackLease(layer); stackLeases.add(lease); }
                lease.fill.sync(layer);
            }
            if (d != null && d.getClass().getName().contains("BlurDrawable")) {
                tintRegion(layer, d, true);
                List<TintedRegion> list = tinted.computeIfAbsent(host, k -> new ArrayList<>());
                TintedRegion entry = null;
                for (TintedRegion r : list) if (r.drawable.get() == d) { entry = r; break; }
                if (entry == null) { entry = new TintedRegion(layer, d); list.add(entry); }
                entry.lease.sync(layer, true);
            } else if (d != null && layer instanceof ViewGroup && !(d instanceof CoveredFill)
                    && (NativeShape.unwrap(d) instanceof GradientDrawable || NativeShape.unwrap(d) instanceof ColorDrawable)
                    && !NativeShape.interactive(d) && !NativeShape.cardLike(layer)) {
                // Samsung can put another opaque fill above the classified card. Keep its
                // geometry/state via DrawableWrapper, suppress only its draw, and restore exactly.
                CoveredFill wrapper = new CoveredFill(d);
                layer.setBackground(wrapper);
                // View.setBackground detached and hid the old background after the wrapper's
                // constructor linked it. Reconnect it so native state/tint changes still propagate.
                d.setCallback(wrapper);
                d.setVisible(wrapper.isVisible(), false);
                remember(covered, host, layer);
            }
            p = layer.getParent();
        }
        companionState(host, true);
    }
    private void companionState(View host, boolean shown) {
        int regions = tinted.containsKey(host) ? tinted.get(host).size() : 0;
        int fills = covered.containsKey(host) ? covered.get(host).size() : 0;
        CompanionSignature old = companionTrace.computeIfAbsent(host, v -> new CompanionSignature());
        if (old.shown == shown && old.regions == regions && old.fills == fills) return;
        old.shown = shown; old.regions = regions; old.fills = fills;
        Probe.log("LIVE_RENDER_CARD", "shown=" + shown + " tintedRegions=" + regions
                + " coveredFills=" + fills + " " + LiveRenderProbe.view(host)
                + " ancestors=" + LiveRenderProbe.ancestors(host));
    }
    private static void remember(WeakHashMap<View, List<WeakReference<View>>> map, View host, View layer) {
        List<WeakReference<View>> list = map.computeIfAbsent(host, k -> new ArrayList<>());
        for (WeakReference<View> ref : list) if (ref.get() == layer) return;
        list.add(new WeakReference<>(layer));
    }
    private static boolean stackFrame(View view) {
        if (view.isClickable() || view.getId() == View.NO_ID) return false;
        try { return "stack_pip_layout".equals(view.getResources().getResourceEntryName(view.getId())); }
        catch (android.content.res.Resources.NotFoundException e) { return false; }
    }
    private void restoreCompanions(View host) {
        List<StackLease> stack = stackFills.remove(host);
        if (stack != null) for (StackLease lease : stack) lease.fill.restore();
        List<WeakReference<View>> buttons = actionButtons.remove(host);
        if (buttons != null) for (WeakReference<View> ref : buttons) {
            View button = ref.get();
            if (button != null) {
                LiveActionFill lease = actionFills.remove(button);
                if (lease != null) lease.restore();
            }
        }
        List<WeakReference<View>> fills = covered.remove(host);
        if (fills != null) for (WeakReference<View> ref : fills) {
            View layer = ref.get();
            if (layer != null) restoreCovered(layer);
        }
        List<TintedRegion> regions = tinted.remove(host);
        if (regions != null) for (TintedRegion ref : regions) {
            View layer = ref.layer.get();
            Drawable d = ref.drawable.get();
            ref.lease.restore();
            if (layer != null && d != null) tintRegion(layer, d, false);
        }
    }

    /** The two DZIF action layouts differ, but their buttons have these exact resource names. */
    private void syncActions(View host) {
        List<View> found = new ArrayList<>();
        findActions(host, found, 0);
        List<WeakReference<View>> prior = actionButtons.get(host);
        if (prior != null) prior.removeIf(ref -> {
            View old = ref.get();
            boolean stale = old == null || !found.contains(old);
            if (stale && old != null) {
                LiveActionFill lease = actionFills.remove(old);
                if (lease != null) lease.restore();
            }
            return stale;
        });
        for (View button : found) {
            LiveActionFill lease = actionFills.computeIfAbsent(button, ignored -> new LiveActionFill());
            lease.sync(button, true);
            if (lease.active()) remember(actionButtons, host, button);
        }
    }

    private static void findActions(View view, List<View> out, int depth) {
        if (depth > 12 || out.size() >= 8) return;
        if (view instanceof android.widget.Button && namedAction(view)) { out.add(view); return; }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount() && out.size() < 8; i++)
            findActions(parent.getChildAt(i), out, depth + 1);
    }

    private static boolean namedAction(View view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(view.getId());
            return "action0".equals(name) || "ongoing_action_text_button".equals(name);
        } catch (RuntimeException e) { return false; }
    }
    private static void restoreCovered(View layer) {
        if (layer.getBackground() instanceof CoveredFill)
            layer.setBackground(((CoveredFill) layer.getBackground()).getDrawable());
    }
    private void tintRegion(View layer, Drawable d, boolean shown) {
        Integer now = regionColor(d);
        if (now == null) return;
        if (shown) {
            Integer orig = regionColors.get(d);
            // The only value we wrote is orig with alpha cleared. Anything else is Samsung's
            // latest value, including an intentionally transparent recolour.
            if (orig == null || now != (orig & 0x00ffffff)) {
                orig = now;
                regionColors.put(d, orig);
            }
            int clear = orig & 0x00ffffff;
            if (now != clear) {
                setRegionColor(layer, d, clear);
                tintState(layer, d, "CLEAR", now, clear);
            }
        } else {
            Integer orig = regionColors.remove(d);
            if (orig != null && !orig.equals(now)) {
                setRegionColor(layer, d, orig);
                tintState(layer, d, "RESTORE", now, orig);
            }
        }
    }
    private void tintState(View layer, Drawable d, String mode, int before, int requested) {
        Integer after = regionColor(d);
        String key = mode + "/" + before + "/" + requested + "/" + after;
        LiveRenderProbe recorder = tintTrace.computeIfAbsent(d, ignored -> new LiveRenderProbe());
        if (recorder.changed(key))
            Probe.log("LIVE_RENDER_REGION_TINT", "mode=" + mode + " before=" + Integer.toHexString(before)
                    + " requested=" + Integer.toHexString(requested)
                    + " actual=" + (after == null ? "?" : Integer.toHexString(after))
                    + " verified=" + (after != null && after == requested)
                    + " " + LiveRenderProbe.view(layer));
    }
    private static final class CoveredFill extends DrawableWrapper {
        CoveredFill(Drawable nativeFill) { super(nativeFill); }
        @Override public void draw(android.graphics.Canvas canvas) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public void getOutline(android.graphics.Outline outline) {
            super.getOutline(outline);
            outline.setAlpha(0f);
        }
        @Override public Drawable mutate() { return this; }
        @Override public ConstantState getConstantState() { return null; }
    }
    /** BackgroundBlurDrawable keeps its colour in a Paint (AOSP mPaint); getColor() where it exists. */
    static Integer regionColor(Drawable d) {
        Object c = NativeShape.call(d, "getColor");
        if (c instanceof Integer) return (Integer) c;
        Object paint = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mPaint");
        if (paint instanceof android.graphics.Paint) return ((android.graphics.Paint) paint).getColor();
        return null;
    }
    /**
     * AOSP BackgroundBlurDrawable.setColor only changes its Paint: unlike setAlpha/setBlurRadius it
     * does not invalidate, so the previous display list (the dark overlay) could stay on screen.
     * The layer is redrawn here, once per real change (callers only come here when the value
     * differs), never per rebind.
     */
    private static void setRegionColor(View layer, Drawable d, int color) {
        try { d.getClass().getMethod("setColor", int.class).invoke(d, color); }
        catch (ReflectiveOperationException | RuntimeException e) { return; }
        d.invalidateSelf();
        layer.invalidate();
    }

    /** A view carrying Samsung's blur: a *BlurDrawable* background or a held SemBlurInfo. */
    boolean blurLayer(View v) {
        Drawable d = v.getBackground();
        if (d != null && !(d instanceof io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable)
                && d.getClass().getName().contains("BlurDrawable")) return true;
        return nativeBlur.held(v) != null;
    }
    /** Whether a native blur layer sits behind the host, between it and the CardStackView. */
    private final Predicate<View> nativeBlurBehind = host -> {
        Object p = host.getParent();
        for (int i = 0; i < 16 && p instanceof View; i++) {
            View x = (View) p;
            if (STACK.equals(x.getClass().getName())) return false;
            if (blurLayer(x)) return activeRegion(x, host);
            p = x.getParent();
        }
        return false;
    };

    /** A SemBlurInfo or a drawable's class name proves structure, not a submitted blur region. */
    static boolean activeRegion(View layer, View host) {
        Drawable d = layer.getBackground();
        if (d == null || !d.getClass().getName().contains("BlurDrawable")
                || !layer.isAttachedToWindow() || !host.isAttachedToWindow()
                || !d.isVisible() || LiveGlass.accumulatedAlpha(layer) <= 0f) return false;
        Object radius = NativeShape.call(d, "getBlurRadius");
        if (!(radius instanceof Number)) radius = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mBlurRadius");
        if (!(radius instanceof Number) || ((Number) radius).floatValue() <= 0f) return false;
        Object a = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mAlpha");
        if ((a instanceof Number && ((Number) a).floatValue() <= 0f) || d.getAlpha() == 0) return false;
        // Bounds alone can exist before RenderThread has recorded any position. An absent/lost
        // position is not proof of blur; use our region until Samsung's drawable is positioned.
        Object position = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mRect");
        if (!(position instanceof Rect) || ((Rect) position).isEmpty()) return false;
        if (!(layer instanceof ViewGroup) || host.getWidth() <= 0 || host.getHeight() <= 0) return false;
        for (Object v = host; v instanceof View; v = ((View) v).getParent()) {
            if (((View) v).getLayerType() == View.LAYER_TYPE_SOFTWARE) return false;
            if (v == layer) break;
        }
        Rect area = new Rect(0, 0, host.getWidth(), host.getHeight());
        try { ((ViewGroup) layer).offsetDescendantRectToMyCoords(host, area); }
        catch (IllegalArgumentException e) { return false; }
        return d.getBounds().contains(area);
    }

    /** The classified root a view belongs to: itself or an ancestor below the stack. */
    private View rootOf(View target) {
        Object p = target;
        for (int i = 0; i < 16 && p instanceof View; i++) {
            if (roots.containsKey(p)) return (View) p;
            if (STACK.equals(p.getClass().getName())) return null;
            p = ((View) p).getParent();
        }
        return null;
    }
}



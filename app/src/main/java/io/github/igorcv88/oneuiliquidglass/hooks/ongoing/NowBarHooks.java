package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * L3: Now Bar card at the bottom of the lockscreen, drawn by the AODService plugin
 * (com.samsung.android.app.aodservice, PluginAODImpl), which SystemUI loads through
 * PluginInstance.loadPlugin into its own classloader. The plugin classes are resolved after that
 * call, once per classloader, from the plugin's loader only (never a global loadClass); a plugin
 * restart brings a new loader and a new, independent install. Without a valid plugin or with any
 * required member missing, the Now Bar stays native.
 *
 * <p>Only the exact NowBarOngoingCardView class is touched. The shared base class
 * ({@code presentation.a}) also serves NowBarMediaCardView, so its writers N/U are followed only
 * when {@code this} is exactly an ongoing card with an eligible item. Writers followed: d0/e0
 * (bind), a.N/a.U (blur, card colour, gradient depth), setViewMode, setExpandedViewColor (the
 * contrast writer, semantically untouched), the stack's refreshAllViews (q) and
 * setExpandedCardState.</p>
 *
 * <p>Glass host: the card container when it is a plain view whose background Samsung writes. A
 * CardView-like container cannot take a foreign background (its own setCardBackgroundColor casts
 * it); there the plugin's blur container carries the glass, only if it covers exactly the same
 * frame, and the CardView colour/elevation are made transparent through its own public setters
 * while the glass is drawn and restored as soon as it is not. The gradient layer is hidden
 * through Samsung's own setGradientBackgroundVisible while the glass is drawn.</p>
 */
final class NowBarHooks {
    static final String PLUGIN_INSTANCE = "com.android.systemui.shared.plugins.PluginInstance";
    static final String AOD_PACKAGE = "com.samsung.android.app.aodservice";
    static final String AOD_COMPONENT = "com.samsung.android.app.aodservice.plugin.PluginAODImpl";
    static final String PRESENTATION = "com.samsung.android.app.facewidget.presentation.";
    static final String ONGOING = PRESENTATION + "NowBarOngoingCardView";
    static final String BASE = PRESENTATION + "a";
    static final String GRADIENT = PRESENTATION + "NowBarCardGradientView";
    static final String STACK = "com.samsung.android.app.facewidget.utils.NowBarCardStackView";
    static final String ITEM = "com.android.systemui.plugins.keyguardstatusview.NowBarItem";
    static final String VIEW_MODE = "com.samsung.android.app.facewidget.common.NowBarConstants$Companion$ViewMode";

    /** Members of one plugin classloader, all verified by name, parameters and return type. */
    static final class Abi {
        final ClassLoader loader;
        final Class<?> ongoing, base, item, stack, gradient;
        final Method container, gradientView, blurContainer, mediaData;
        final Method d0, e0, n, u, viewMode, expandedColor, stackRefresh, expandedState, gradientVisible;
        Abi(ClassLoader cl) {
            loader = cl;
            ongoing = HookSite.type(ONGOING, cl);
            base = HookSite.type(BASE, cl);
            item = HookSite.type(ITEM, cl);
            stack = HookSite.type(STACK, cl);
            gradient = HookSite.type(GRADIENT, cl);
            Class<?> mode = HookSite.type(VIEW_MODE, cl);
            boolean family = ongoing != null && base != null && ongoing.getSuperclass() == base && View.class.isAssignableFrom(ongoing);
            container = family ? viewGetter(base, "getCardViewContainer") : null;
            gradientView = family ? viewGetter(base, "getGradientBgView") : null;
            blurContainer = family ? viewGetter(base, "getBlurContainer") : null;
            mediaData = item != null ? anyGetter(item, "getFaceWidgetMediaData") : null;
            d0 = family ? HookSite.exact(ongoing, "d0", void.class, item) : null;
            e0 = family ? HookSite.exact(ongoing, "e0", void.class, item, boolean.class) : null;
            n = family ? HookSite.exact(base, "N", void.class, item, View.class, float.class, int.class) : null;
            u = family ? HookSite.exact(base, "U", void.class, item, Integer.class) : null;
            viewMode = family && mode != null ? HookSite.exact(ongoing, "setViewMode", void.class, mode) : null;
            expandedColor = family ? HookSite.exact(ongoing, "setExpandedViewColor", void.class, float.class) : null;
            stackRefresh = stack != null ? HookSite.exact(stack, "q", void.class, CopyOnWriteArrayList.class) : null;
            expandedState = stack != null ? HookSite.exact(stack, "setExpandedCardState", void.class, boolean.class) : null;
            gradientVisible = gradient != null ? HookSite.exact(gradient, "setGradientBackgroundVisible", void.class, boolean.class) : null;
        }
        /** The minimum contract: exact classes, media veto readable, a container and a bind writer. */
        boolean usable() { return ongoing != null && base != null && item != null && container != null && mediaData != null && (d0 != null || e0 != null); }
        String missing() {
            StringBuilder b = new StringBuilder();
            if (ongoing == null) b.append(" ongoing"); if (base == null) b.append(" base"); if (item == null) b.append(" item");
            if (container == null) b.append(" container"); if (mediaData == null) b.append(" mediaData");
            if (d0 == null && e0 == null) b.append(" bind");
            return b.toString().trim();
        }
        private static Method viewGetter(Class<?> c, String name) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (!View.class.isAssignableFrom(m.getReturnType())) return null;
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | LinkageError | RuntimeException e) { return null; }
        }
        private static Method anyGetter(Class<?> c, String name) {
            try {
                Method m = c.getMethod(name);
                return m.getReturnType().isPrimitive() ? null : m;
            } catch (NoSuchMethodException | LinkageError | RuntimeException e) { return null; }
        }
    }

    /** Per card: the item it shows, the layers found, the native CardView values put aside. */
    private static final class Card {
        WeakReference<Object> item = new WeakReference<>(null);
        WeakReference<View> host = new WeakReference<>(null), container = new WeakReference<>(null), gradient = new WeakReference<>(null);
        ColorStateList nativeCardColor;
        Float nativeElevation;
        boolean gradientHidden;
        Abi abi;
    }

    private final LiveGlass glass;
    private final HookSite site;
    private final Map<ClassLoader, Abi> loaders = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    /** Last visibility each gradient view was asked for (only those inside a tracked card matter). */
    private final WeakHashMap<View, Boolean> gradientRequested = new WeakHashMap<>();
    /** One-shot layout checks waiting for a CardView card's first layout. */
    private final WeakHashMap<View, Boolean> pendingLayout = new WeakHashMap<>();
    /** Installs the plugin hooks of a verified ABI (production: through the HookSite). */
    interface Installer { void install(Abi abi); }
    private Installer installer;

    NowBarHooks(LiveGlass glass, HookSite site) { this.glass = glass; this.site = site; }
    void setInstaller(Installer i) { installer = i; }

    /**
     * After PluginInstance.loadPlugin(). True if this call installed the hooks of a new plugin
     * classloader. Any other plugin, a missing plugin object or a loader already handled: false.
     */
    boolean pluginLoaded(Object instance) {
        if (instance == null) return false;
        Object pkg = NativeShape.call(instance, "getPackageName");
        if (!AOD_PACKAGE.equals(pkg)) return false;
        Object component = NativeShape.call(instance, "getComponentName");
        if (!(component instanceof ComponentName) || !AOD_COMPONENT.equals(((ComponentName) component).getClassName())) return false;
        Object plugin = NativeShape.call(instance, "getPlugin");
        if (plugin == null) return false;
        ClassLoader cl = plugin.getClass().getClassLoader();
        // The plugin must come from its own loader, not SystemUI's or the framework's.
        if (cl == null || cl == instance.getClass().getClassLoader() || cl == View.class.getClassLoader()) return false;
        synchronized (loaders) {
            if (loaders.containsKey(cl)) return false;
            Abi abi = new Abi(cl);
            loaders.put(cl, abi);
            if (!abi.usable()) {
                Probe.log("NOWBAR_UNAVAILABLE", "missing=" + abi.missing() + " fallback=native");
                return false;
            }
            Probe.log("NOWBAR_PLUGIN", "loader=" + Integer.toHexString(System.identityHashCode(cl)) + " stack=" + (abi.stackRefresh != null)
                    + " expanded=" + (abi.expandedState != null) + " n=" + (abi.n != null) + " u=" + (abi.u != null)
                    + " gradient=" + (abi.gradientVisible != null) + " blurContainer=" + (abi.blurContainer != null));
            if (installer != null) installer.install(abi);
            return true;
        }
    }
    int loaderCount() { return loaders.size(); }

    /** After d0(item) / e0(item, z) on the ongoing card, and N/U of the base with this card. */
    void cardBound(Abi abi, View card, Object item) {
        if (card == null || glass.writing() || card.getClass() != abi.ongoing) return;
        Card c = cards.get(card);
        if (c == null) { c = new Card(); c.abi = abi; cards.put(card, c); }
        if (item != null) c.item = new WeakReference<>(item);
        Object it = c.item.get();
        LiveEligibility.Verdict verdict = LiveEligibility.nowBar(card, it);
        View container = view(abi.container, card);
        if (container == null) { release(card, c, "noContainer"); return; }
        c.container = new WeakReference<>(container);
        c.gradient = new WeakReference<>(view(abi.gradientView, card));
        View host = container;
        boolean emptyNative = false;
        if (NativeShape.cardLike(container)) {
            View blurC = view(abi.blurContainer, card);
            if (blurC == null || blurC == container || NativeShape.cardLike(blurC)) { release(card, c, "cardviewWithoutLayer"); return; }
            if (container.getWidth() == 0 || blurC.getWidth() == 0) {
                // Sizes unknown before the first layout: decided in the pre-draw that follows it.
                if (verdict.ok()) awaitLayout(abi, card);
                if (!glass.tracked(blurC)) return;
            } else if (!sameFrame(blurC, container, card)) { release(card, c, "frameMismatch"); return; }
            host = blurC;
            emptyNative = true;
        }
        View previous = c.host.get();
        if (previous != null && previous != host) glass.forget(previous, "hostChanged");
        c.host = new WeakReference<>(host);
        // The tracked state is the value of a weak map keyed by a child of the card: it must not
        // hold the card strongly, or the card would never be collected.
        final Card fc = c;
        final WeakReference<View> weakCard = new WeakReference<>(card);
        LiveGlass.Options options = new LiveGlass.Options();
        options.allowEmptyNative = emptyNative;
        options.onShown = (v, shown) -> { View k = weakCard.get(); if (k != null) companions(k, fc, shown); };
        options.shapeSource = emptyNative ? container : null;
        glass.offer(host, LiveSurface.NOWBAR, it, verdict, i -> { View k = weakCard.get(); return k != null && LiveEligibility.nowBar(k, i).ok(); }, options);
        if (!glass.applied(host)) companions(card, c, false);
        else if (glass.glassOf(host) != null && glass.glassOf(host).showsGlass()) companions(card, c, true);
    }

    /** After the stack's q (refreshAllViews) and setExpandedCardState: its ongoing cards only. */
    void stackChanged(View stack) {
        if (stack == null || glass.writing()) return;
        for (Map.Entry<View, Card> e : new java.util.ArrayList<>(cards.entrySet())) {
            View card = e.getKey();
            if (card != null && LiveChipHooks.within(card, stack)) cardBound(e.getValue().abi, card, null);
        }
    }

    /** Before setGradientBackgroundVisible(z): returns the value to use (hidden under glass). */
    boolean gradientVisible(View gradient, boolean requested) {
        gradientRequested.put(gradient, requested);
        for (Card c : cards.values()) if (c.gradient.get() == gradient && c.gradientHidden) return false;
        return requested;
    }

    private void companions(View card, Card c, boolean shown) {
        View container = c.container.get();
        if (container != null && NativeShape.cardLike(container) && c.host.get() != container) cardTransparent(container, c, shown);
        View gradient = c.gradient.get();
        Method setter = c.abi == null ? null : c.abi.gradientVisible;
        if (gradient == null || setter == null || gradient.getClass() != c.abi.gradient) { c.gradientHidden = false; return; }
        if (shown == c.gradientHidden) return;
        c.gradientHidden = shown;
        Boolean asked = gradientRequested.get(gradient);
        try {
            if (shown) site.invokeOriginal(setter, gradient, false);
            else if (asked != null) site.invokeOriginal(setter, gradient, asked);
        } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NOWBAR_GRADIENT_FAILED", e); }
    }

    /** CardView colour and elevation through its own setters; natives recorded once, restored exactly. */
    private void cardTransparent(View container, Card c, boolean on) {
        try {
            if (on) {
                Object color = NativeShape.call(container, "getCardBackgroundColor");
                if (color instanceof ColorStateList && ((ColorStateList) color).getDefaultColor() != 0) c.nativeCardColor = (ColorStateList) color;
                Object elevation = NativeShape.call(container, "getCardElevation");
                if (elevation instanceof Float && (Float) elevation != 0f) c.nativeElevation = (Float) elevation;
                container.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(container, ColorStateList.valueOf(0));
                if (c.nativeElevation != null) container.getClass().getMethod("setCardElevation", float.class).invoke(container, 0f);
            } else {
                if (c.nativeCardColor != null) container.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(container, c.nativeCardColor);
                if (c.nativeElevation != null) container.getClass().getMethod("setCardElevation", float.class).invoke(container, c.nativeElevation);
                c.nativeCardColor = null;
                c.nativeElevation = null;
            }
        } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NOWBAR_CARD_COLOR_FAILED", e); }
    }

    private void release(View card, Card c, String reason) {
        View host = c.host.get();
        if (host != null) glass.forget(host, reason);
        companions(card, c, false);
        c.host = new WeakReference<>(null);
        Probe.log("NOWBAR_NATIVE", "reason=" + reason);
    }

    private void awaitLayout(Abi abi, View card) {
        if (pendingLayout.containsKey(card)) return;
        pendingLayout.put(card, Boolean.TRUE);
        android.view.ViewTreeObserver o = card.getViewTreeObserver();
        o.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (card.getWidth() == 0) return true;
                pendingLayout.remove(card);
                if (card.getViewTreeObserver().isAlive()) card.getViewTreeObserver().removeOnPreDrawListener(this);
                cardBound(abi, card, null);
                return true;
            }
        });
    }

    /** Whether two views cover the same rectangle of {@code root} (within 2 px), translations ignored. */
    static boolean sameFrame(View a, View b, View root) {
        int[] pa = offset(a, root), pb = offset(b, root);
        if (pa == null || pb == null) return false;
        return Math.abs(pa[0] - pb[0]) <= 2 && Math.abs(pa[1] - pb[1]) <= 2
                && Math.abs(a.getWidth() - b.getWidth()) <= 2 && Math.abs(a.getHeight() - b.getHeight()) <= 2;
    }
    private static int[] offset(View v, View root) {
        int x = 0, y = 0;
        Object p = v;
        for (int i = 0; i < 16 && p instanceof View; i++) {
            if (p == root) return new int[]{x, y};
            View q = (View) p;
            x += q.getLeft(); y += q.getTop();
            p = q.getParent();
        }
        return null;
    }
    private static View view(Method getter, Object card) {
        if (getter == null) return null;
        try { Object v = getter.invoke(card); return v instanceof View ? (View) v : null; }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
}

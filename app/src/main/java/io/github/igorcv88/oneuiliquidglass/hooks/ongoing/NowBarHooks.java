package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.diagnostics.LiveRenderProbe;

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
 * <p>Glass host: the card container when it is a plain view whose background Samsung writes. On
 * DZIF the container is an androidx CardView and getBlurContainer() returns that same CardView
 * (field {@code a.J}) or null, so there is no separate layer to use, and a CardView's background
 * cannot be replaced (its own setCardBackgroundColor casts it). The glass is drawn on a
 * {@link GlassLayer} added inside the CardView, over its whole frame and behind Samsung's content,
 * only for an eligible card with the surface on; the CardView colour/elevation are made
 * transparent through its own public setters while the glass is drawn and restored as soon as it
 * is not; the gradient layer is hidden through Samsung's own setGradientBackgroundVisible
 * meanwhile. Releasing removes the layer, leaving Samsung's hierarchy as it was.</p>
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
        final Method container, gradientView, blurContainer;
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
        /** The minimum contract: exact classes, a container and a bind writer. */
        boolean usable() { return ongoing != null && base != null && item != null && container != null && (d0 != null || e0 != null); }
        String missing() {
            StringBuilder b = new StringBuilder();
            if (ongoing == null) b.append(" ongoing"); if (base == null) b.append(" base"); if (item == null) b.append(" item");
            if (container == null) b.append(" container");
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
    }

    /** Per card: the item it shows, the layers found, the native CardView values put aside. */
    private static final class Card {
        WeakReference<Object> item = new WeakReference<>(null);
        WeakReference<Object> packageItem = new WeakReference<>(null);
        String iconPackage;
        WeakReference<View> host = new WeakReference<>(null), container = new WeakReference<>(null), gradient = new WeakReference<>(null);
        ColorStateList nativeCardColor;
        final NativeRegionLease nativeRegion = new NativeRegionLease();
        final NowBarIconLease icon = new NowBarIconLease();
        /** The expanded notification header inflates a separate AdaptiveIconDrawable. */
        final NowBarIconLease expandedIcon = new NowBarIconLease();
        // DZIF can inflate a second ongoing icon (Clock's right image or the app's
        // separate left_icon). Each lease owns only its exact ImageView.
        final NowBarIconLease expandedOtherIcon = new NowBarIconLease();
        final NowBarIconLease expandedInfoIcon = new NowBarIconLease();
        Float nativeElevation;
        boolean gradientHidden;
        WeakReference<View> hiddenGradient = new WeakReference<>(null);
        Integer nativeGradientVisibility;
        Abi abi;
        /** The glass layer added inside the CardView container (DZIF), or none. */
        WeakReference<GlassLayer> layer = new WeakReference<>(null);
        /** Last native reason logged for this card: the same state is never logged twice. */
        String lastNative;
        /** Trace-only, bounded diagnostic of the actual compact/expanded icon views. */
        long nextIconProbe;
        String lastIconProbe;
    }

    private final LiveGlass glass;
    private final HookSite site;
    /** Lockscreen media capsule (NowBarMediaCardView), compact only: its own lens path. */
    final LockMediaGlass media;
    private final Map<ClassLoader, Abi> loaders = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakHashMap<View, Card> cards = new WeakHashMap<>();
    /** Last visibility each gradient view was asked for (only those inside a tracked card matter). */
    private final WeakHashMap<View, Boolean> gradientRequested = new WeakHashMap<>();
    /** Installs the plugin hooks of a verified ABI (production: through the HookSite). */
    interface Installer { void install(Abi abi); }
    private Installer installer;

    NowBarHooks(LiveGlass glass, HookSite site) { this.glass = glass; this.site = site; this.media = new LockMediaGlass(glass); }
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

    /** Trace-only inventory after an ongoing notification is removed; never mutates a card. */
    String diagnosticSummary() {
        int layers = 0, visible = 0, withItem = 0;
        for (Map.Entry<View, Card> entry : new java.util.ArrayList<>(cards.entrySet())) {
            View view = entry.getKey();
            Card card = entry.getValue();
            if (view == null) continue;
            if (view.isAttachedToWindow() && view.isShown()) visible++;
            if (card.item.get() != null) withItem++;
            GlassLayer layer = card.layer.get();
            if (layer != null && layer.getParent() != null) layers++;
        }
        return "cards=" + cards.size() + " visible=" + visible + " withItem=" + withItem
                + " layers=" + layers;
    }

    /** After d0(item) / e0(item, z) on the ongoing card, and N/U of the base with this card. */
    void cardBound(Abi abi, View card, Object item) {
        if (card == null || glass.writing() || card.getClass() != abi.ongoing) return;
        Card c = cards.get(card);
        if (c == null) { c = new Card(); c.abi = abi; cards.put(card, c); }
        if (item != null) c.item = new WeakReference<>(item);
        Object it = c.item.get();
        if (c.packageItem.get() != it) {
            c.iconPackage = iconPackage(it);
            c.packageItem = new WeakReference<>(it);
        }
        LiveEligibility.Verdict verdict = LiveEligibility.nowBar(card, it);
        View container = view(abi.container, card);
        if (container == null) { release(card, c, "noContainer"); return; }
        c.container = new WeakReference<>(container);
        c.gradient = new WeakReference<>(view(abi.gradientView, card));
        View host = container;
        boolean emptyNative = false;
        if (NativeShape.cardLike(container)) {
            // DZIF: the container and the "blur container" are the same CardView, whose own
            // background must stay its own drawable class. The glass goes on a layer inside it,
            // created only for an eligible card with the surface on, so nothing is added otherwise.
            if (!verdict.ok()) { release(card, c, "verdict=" + verdict); return; }
            if (!glass.allows(LiveSurface.NOWBAR)) { release(card, c, "gate"); return; }
            if (!(container instanceof ViewGroup)) { release(card, c, "containerNotGroup"); return; }
            host = layer(c, (ViewGroup) container);
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
        options.onSync = options.onShown;
        options.shapeSource = emptyNative ? container : null;
        glass.offer(host, LiveSurface.NOWBAR, it, verdict, i -> { View k = weakCard.get(); return k != null && LiveEligibility.nowBar(k, i).ok(); }, options);
        c.lastNative = null;
        if (!glass.applied(host)) companions(card, c, false);
        else if (glass.glassOf(host) != null && glass.glassOf(host).showsGlass()) companions(card, c, true);
        if (verdict.ok()) Probe.liveSurfaceLater(card, "NOWBAR", 550);
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
        for (Card c : cards.values()) if (c.gradient.get() == gradient && c.gradientHidden
                && c.nativeGradientVisibility == null) return false;
        return requested;
    }

    private void companions(View card, Card c, boolean shown) {
        View container = c.container.get();
        c.nativeRegion.sync(container, shown && c.host.get() != container);
        if (container != null && NativeShape.cardLike(container) && c.host.get() != container) cardTransparent(container, c, shown);
        String packageName = c.iconPackage;
        ImageView icon = c.icon.target();
        if (icon == null || !LiveChipHooks.within(icon, card)) icon = findIcon(card);
        c.icon.sync(icon, shown, packageName);
        // On DZIF the expanded card uses a NotificationRowIconView under
        // expand_content_rootview, not the compact nowbar_icon. Its adaptive icon
        // includes a coloured background; lease just its monochrome glyph.
        ImageView expandedIcon = findExpandedIcon(card);
        Drawable expandedBefore = expandedIcon == null ? null : expandedIcon.getDrawable();
        c.expandedIcon.sync(expandedIcon, shown, packageName);
        View expandedRoot = NativeIds.find(card, "expand_content_rootview");
        ImageView other = expandedRoot == null ? null : expandedOtherIcon(expandedRoot, expandedIcon);
        c.expandedOtherIcon.sync(other, shown, packageName);
        ImageView info = expandedRoot == null ? null : expandedInfoIcon(expandedRoot, expandedIcon, other);
        c.expandedInfoIcon.sync(info, shown, packageName);
        if (expandedIcon != null && expandedBefore != expandedIcon.getDrawable()) {
            Drawable after = expandedIcon.getDrawable();
            Probe.log("NOWBAR_EXPANDED_ICON", "glass=" + shown
                    + " before=" + (expandedBefore == null ? "null" : expandedBefore.getClass().getSimpleName())
                    + " after=" + (after == null ? "null" : after.getClass().getSimpleName()));
        }
        // DZIF uses a different CardView for the EXPANDED ongoing app icon; the normal
        // nowbar_icon lease only clears its compact disc. Never touch MediaCardView.
        c.icon.syncExpandedDisc(findExpandedIconContainer(card, expandedIcon), shown);
        traceIcons(card, c, icon, expandedIcon, shown);
        View gradient = c.gradient.get();
        View hidden = c.hiddenGradient.get();
        if (c.nativeGradientVisibility != null && (!shown || hidden != gradient)) {
            if (hidden != null && hidden.getVisibility() == View.INVISIBLE)
                hidden.setVisibility(c.nativeGradientVisibility);
            c.nativeGradientVisibility = null;
            c.hiddenGradient.clear();
            c.gradientHidden = false;
        }
        Method setter = c.abi == null ? null : c.abi.gradientVisible;
        if (gradient == null || c.abi == null || gradient.getClass() != c.abi.gradient) { c.gradientHidden = false; return; }
        // Some plugin ABIs have the writer but no readable gradient flag. The exact dedicated
        // gradient view can still be hidden reversibly; do not guess its private boolean fields.
        if (shown && (setter == null || gradientState(gradient) == null)
                && !gradientRequested.containsKey(gradient)) {
            if (c.nativeGradientVisibility == null || gradient.getVisibility() != View.INVISIBLE)
                c.nativeGradientVisibility = gradient.getVisibility();
            c.hiddenGradient = new WeakReference<>(gradient);
            c.gradientHidden = true;
            if (gradient.getVisibility() != View.INVISIBLE) gradient.setVisibility(View.INVISIBLE);
            return;
        }
        if (c.nativeGradientVisibility != null && shown) {
            if (gradient.getVisibility() != View.INVISIBLE) {
                c.nativeGradientVisibility = gradient.getVisibility();
                gradient.setVisibility(View.INVISIBLE);
            }
            return;
        }
        if (setter == null) return;
        if (shown == c.gradientHidden) return;
        c.gradientHidden = shown;
        Boolean asked = gradientRequested.get(gradient);
        if (shown && asked == null) {
            // Never seen through the setter: read its current state, or leave it untouched when
            // it cannot be recovered (hiding it then could never be undone exactly).
            asked = gradientState(gradient);
            if (asked == null) { c.gradientHidden = false; return; }
            gradientRequested.put(gradient, asked);
        }
        try {
            if (shown) site.invokeOriginal(setter, gradient, false);
            else if (asked != null) site.invokeOriginal(setter, gradient, asked);
            Probe.log("LIVE_RENDER_NOWBAR_GRADIENT", "shown=" + shown + " requested=" + asked
                    + " observed=" + gradientState(gradient) + " " + LiveRenderProbe.view(gradient));
        } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NOWBAR_GRADIENT_FAILED", e); }
    }

    /**
     * The verified DZIF resource id on NowBarOngoingCardView's expanded app-icon CardView. Called
     * from every pre-draw (onSync): the id is resolved once and found by int, and the CardView test
     * runs only on that one view (DZIF simpleperf 04/11: the previous walk ran reflection on every
     * node and dominated SystemUI's main thread).
     */
    private static View findExpandedIconContainer(View card, ImageView icon) {
        View v = NativeIds.find(card, "ongoing_app_expanded_icon_container");
        if (v != null && NativeShape.cardLike(v)) return v;
        // Alternate Samsung layouts put a separate, icon-sized CardView directly
        // around the notification header's icon. Never clear the whole ongoing card.
        if (icon == null) return null;
        View root = NativeIds.find(card, "expand_content_rootview");
        for (android.view.ViewParent p = icon.getParent(); p instanceof View && p != root;
             p = p.getParent()) {
            View candidate = (View) p;
            if (!NativeShape.cardLike(candidate)) continue;
            int w = candidate.getWidth(), h = candidate.getHeight();
            if (w > 0 && h > 0 && icon.getWidth() > 0 && icon.getHeight() > 0
                    && w <= icon.getWidth() * 3 && h <= icon.getHeight() * 3) return candidate;
        }
        return null;
    }

    /** Constrain every lookup to the ongoing card's expanded notification content. */
    private static ImageView findExpandedIcon(View card) {
        View expanded = NativeIds.find(card, "expand_content_rootview");
        return expanded == null ? null : expandedHeaderIcon(expanded);
    }

    static ImageView expandedHeaderIcon(View expanded) {
        // Clock's ID is different from Gradus/standard Android notification headers.
        for (String id : new String[]{"ongoing_activity_expand_left_image", "icon", "left_icon"}) {
            View v = NativeIds.find(expanded, id);
            if (v instanceof ImageView) return (ImageView) v;
        }
        return null;
    }

    static ImageView expandedOtherIcon(View expanded, ImageView primary) {
        for (String id : new String[]{"ongoing_activity_expand_right_image", "right_icon",
                "left_icon", "icon"}) {
            View v = NativeIds.find(expanded, id);
            if (v instanceof ImageView && v != primary) return (ImageView) v;
        }
        return null;
    }

    static ImageView expandedInfoIcon(View expanded, ImageView primary, ImageView secondary) {
        View v = NativeIds.find(expanded, "ongoing_activity_secondary_info_icon");
        return v instanceof ImageView && v != primary && v != secondary ? (ImageView) v : null;
    }

    /** Samsung sometimes exposes a display label in getNowBarPackage (e.g. "Timer").
     * Never query PackageManager with a UI label; use a separately verified package field. */
    static String iconPackage(Object item) {
        if (item == null) return null;
        for (String method : new String[]{"getPackageName", "getAppPackageName",
                "getNotificationPackage", "getSourcePackage", "getNowBarPackage"}) {
            Object value = NativeShape.call(item, method);
            if (value instanceof String && packageName((String) value)) return (String) value;
        }
        for (String field : new String[]{"mPackageName", "packageName",
                "mAppPackageName", "mNotificationPackage"}) {
            Object value = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(item, field);
            if (value instanceof String && packageName((String) value)) return (String) value;
        }
        return null;
    }

    private static boolean packageName(String value) {
        return value.matches("[a-zA-Z_][a-zA-Z_0-9]*(\\.[a-zA-Z_][a-zA-Z_0-9]*)+");
    }

    /**
     * The AOD plugin inflates distinct compact/expanded icons. Every eligible app can supply a
     * different drawable (e.g. Clock's VectorDrawable or Maps' launcher bitmap), so an app-only
     * probe missed the failing expanded views. This diagnostic reads no notification text and is
     * capped per card to once per two seconds, with output only when the route changes.
     */
    private static void traceIcons(View card, Card c, ImageView compact, ImageView expanded,
                                   boolean shown) {
        if (!Probe.trace) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now < c.nextIconProbe) return;
        c.nextIconProbe = now + 2000;
        Object item = c.item.get();
        Object pkgValue = item == null ? null : NativeShape.call(item, "getNowBarPackage");
        String pkg = pkgValue instanceof String ? (String) pkgValue : "unavailable";
        View expandedRoot = NativeIds.find(card, "expand_content_rootview");
        View iconContainer = findExpandedIconContainer(card, expanded);
        String state = "pkg=" + pkg + " shown=" + shown
                + " compact=" + iconState(compact)
                + " expandedRoot=" + (expandedRoot == null ? "missing" : expandedRoot.getClass().getSimpleName())
                + " expanded=" + iconState(expanded)
                + " right=" + iconState(expandedRoot == null ? null : expandedOtherIcon(expandedRoot, expanded))
                + " info=" + iconState(expandedRoot == null ? null : expandedInfoIcon(
                        expandedRoot, expanded, expandedOtherIcon(expandedRoot, expanded)))
                + " expandedDisc=" + (iconContainer == null ? "missing" : iconContainer.getClass().getSimpleName())
                + " expandedParent=" + (expanded == null || expanded.getParent() == null
                        ? "missing" : expanded.getParent().getClass().getSimpleName())
                + " expandedBg=" + (expanded == null || expanded.getBackground() == null
                        ? "none" : expanded.getBackground().getClass().getSimpleName());
        if (!state.equals(c.lastIconProbe)) {
            c.lastIconProbe = state;
            Probe.log("NOWBAR_ICON_DIAG", state);
            if (expanded == null && expandedRoot != null)
                Probe.log("NOWBAR_ICON_CANDIDATES", "pkg=" + pkg + " views=" + candidateIds(expandedRoot));
        }
    }

    static String iconState(ImageView image) {
        if (image == null) return "missing";
        Drawable drawable = image.getDrawable();
        String type = drawable == null ? "null" : drawable.getClass().getSimpleName();
        boolean adaptiveMono = drawable instanceof AdaptiveIconDrawable
                && ((AdaptiveIconDrawable) drawable).getMonochrome() != null;
        return type + "{mono=" + adaptiveMono + ",state="
                + (drawable != null && drawable.getConstantState() != null)
                + ",attached=" + image.isAttachedToWindow()
                + ",visible=" + image.isShown()
                + ",imageTint=" + (image.getImageTintList() != null)
                + ",filter=" + (image.getColorFilter() != null) + "}";
    }

    /** Only resource identifiers and view classes; at most eight entries and ten levels deep. */
    private static String candidateIds(View root) {
        StringBuilder out = new StringBuilder();
        scanCandidates(root, out, new int[]{0}, 0);
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void scanCandidates(View v, StringBuilder out, int[] count, int depth) {
        if (v == null || depth > 10 || count[0] >= 8) return;
        if (v instanceof ImageView) {
            if (out.length() > 0) out.append(',');
            out.append(v.getClass().getSimpleName()).append(':');
            try { out.append(v.getResources().getResourceEntryName(v.getId())); }
            catch (RuntimeException e) { out.append("no-id"); }
            count[0]++;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount() && count[0] < 8; i++)
                scanCandidates(group.getChildAt(i), out, count, depth + 1);
        }
    }

    /** No changes to vector-only icons or sibling UI outside NowBarOngoingCardView. */
    private static ImageView findIcon(View card) {
        View v = NativeIds.find(card, "nowbar_icon");
        return v instanceof ImageView ? (ImageView) v : null;
    }

    /** The gradient view's own visibility getter, if the plugin has one; null otherwise. */
    static Boolean gradientState(View gradient) {
        for (String name : new String[]{"isGradientBackgroundVisible", "getGradientBackgroundVisible"}) {
            Object v = NativeShape.call(gradient, name);
            if (v instanceof Boolean) return (Boolean) v;
        }
        return null;
    }

    /** CardView colour and elevation through its own setters; natives recorded once, restored exactly. */
    private void cardTransparent(View container, Card c, boolean on) {
        try {
            boolean changed = false;
            if (on) {
                // Written only when Samsung put a value back: the Now Bar rebinds dozens of times per
                // second, and a setter per call would invalidate the CardView on every one.
                Object color = NativeShape.call(container, "getCardBackgroundColor");
                if (color instanceof ColorStateList && ((ColorStateList) color).getDefaultColor() != 0) {
                    c.nativeCardColor = (ColorStateList) color;
                    container.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(container, ColorStateList.valueOf(0));
                    changed = true;
                }
                Object elevation = NativeShape.call(container, "getCardElevation");
                if (elevation instanceof Float && (Float) elevation != 0f) {
                    c.nativeElevation = (Float) elevation;
                    container.getClass().getMethod("setCardElevation", float.class).invoke(container, 0f);
                    changed = true;
                }
            } else {
                changed = c.nativeCardColor != null || c.nativeElevation != null;
                if (c.nativeCardColor != null) container.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(container, c.nativeCardColor);
                if (c.nativeElevation != null) container.getClass().getMethod("setCardElevation", float.class).invoke(container, c.nativeElevation);
                c.nativeCardColor = null;
                c.nativeElevation = null;
            }
            if (changed) {
                Object actual = NativeShape.call(container, "getCardBackgroundColor");
                Object elevation = NativeShape.call(container, "getCardElevation");
                Probe.log("LIVE_RENDER_NOWBAR_CARD", "glass=" + on
                        + " actualColor=" + (actual instanceof ColorStateList
                                ? Integer.toHexString(((ColorStateList) actual).getDefaultColor()) : "?")
                        + " elevation=" + (elevation instanceof Number ? elevation : "?")
                        + " " + LiveRenderProbe.view(container)
                        + " ancestors=" + LiveRenderProbe.ancestors(container));
            }
        } catch (ReflectiveOperationException | RuntimeException e) { Probe.error("NOWBAR_CARD_COLOR_FAILED", e); }
    }

    private void release(View card, Card c, String reason) {
        // Already native for this reason with nothing acquired since: companions(true) needs a
        // host, so with no host and no layer there is no state to put back.
        if (c.host.get() == null && c.layer.get() == null && reason.equals(c.lastNative)) return;
        View host = c.host.get();
        if (host != null) glass.forget(host, reason);
        companions(card, c, false);
        c.host = new WeakReference<>(null);
        GlassLayer layer = c.layer.get();
        if (layer != null && layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
        c.layer = new WeakReference<>(null);
        if (!reason.equals(c.lastNative)) { c.lastNative = reason; Probe.log("NOWBAR_NATIVE", "reason=" + reason); }
    }

    /** The card's glass layer inside its CardView, added once and re-added if Samsung dropped it. */
    private GlassLayer layer(Card c, ViewGroup container) {
        GlassLayer layer = c.layer.get();
        if (layer == null) { layer = new GlassLayer(container.getContext()); c.layer = new WeakReference<>(layer); }
        if (layer.getParent() != container) {
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            container.addView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Probe.log("LIVE_RENDER_NOWBAR_LAYER", "attachedToCard=true " + LiveRenderProbe.view(layer)
                    + " container=" + LiveRenderProbe.view(container));
        }
        return layer;
    }

    /** Config changed: every known ongoing card is evaluated again (adds or removes its layer). */
    void refreshAll() {
        for (Map.Entry<View, Card> e : new java.util.ArrayList<>(cards.entrySet()))
            if (e.getKey() != null && e.getValue().abi != null) cardBound(e.getValue().abi, e.getKey(), null);
        media.refreshAll();
    }

    private static View view(Method getter, Object card) {
        if (getter == null) return null;
        try { Object v = getter.invoke(card); return v instanceof View ? (View) v : null; }
        catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
}


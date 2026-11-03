package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.plugins.keyguardstatusview.NowBarItem;
import com.android.systemui.shared.plugins.PluginInstance;
import com.samsung.android.app.facewidget.presentation.NowBarMediaCardView;
import com.samsung.android.app.facewidget.presentation.NowBarOngoingCardView;
import com.samsung.android.app.facewidget.presentation.a;
import com.samsung.android.app.facewidget.utils.NowBarCardStackView;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** L3: plugin bootstrap once per classloader, exact ongoing class, media excluded, CardView layering. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NowBarHooksTest {
    private Env env;
    private LiveGlass glass;
    private RecordingSite site;
    private NowBarHooks hooks;
    private NowBarHooks.Abi abi;
    private Activity activity;
    private final List<NowBarHooks.Abi> installs = new ArrayList<>();

    /** Defines the plugin class itself, so the plugin object comes from a loader of its own. */
    static final class PluginLoader extends ClassLoader {
        private final boolean hideSamsung;
        PluginLoader(ClassLoader parent, boolean hideSamsung) { super(parent); this.hideSamsung = hideSamsung; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                if (hideSamsung && name.startsWith("com.samsung.")) throw new ClassNotFoundException(name);
                if (!name.equals(FakePlugin.class.getName())) return super.loadClass(name, resolve);
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                    byte[] bytes = out.toByteArray();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (java.io.IOException | RuntimeException e) { throw new ClassNotFoundException(name, e); }
            }
        }
    }
    public static class FakePlugin { }

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        site = new RecordingSite();
        hooks = new NowBarHooks(glass, site);
        hooks.setInstaller(installs::add);
        abi = new NowBarHooks.Abi(getClass().getClassLoader());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        a.containerFactory = null;
    }
    @After public void tearDown() { a.containerFactory = null; }

    private Object plugin(boolean hideSamsung) throws Exception {
        return new PluginLoader(getClass().getClassLoader(), hideSamsung).loadClass(FakePlugin.class.getName()).getDeclaredConstructor().newInstance();
    }
    private PluginInstance instance(String pkg, String cls, Object plugin) {
        PluginInstance p = new PluginInstance(pkg, cls, plugin);
        p.loadPlugin();
        return p;
    }

    @Test public void pluginBootstrapIsFilteredAndRunsOncePerClassloader() throws Exception {
        assertFalse(hooks.pluginLoaded(instance("com.other.plugin", NowBarHooks.AOD_COMPONENT, plugin(false))));
        assertFalse(hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, "com.samsung.Other", plugin(false))));
        assertFalse(hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, null)));
        assertFalse("a plugin from SystemUI's own loader is not the AOD plugin",
                hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, new Object())));
        Object p = plugin(false);
        assertTrue("plugin loaded after SystemUI: installs", hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, p)));
        assertFalse("same classloader: never twice", hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, p)));
        assertEquals(1, installs.size());
        assertTrue("restarted plugin, new loader: its own install", hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, plugin(false))));
        assertEquals(2, installs.size());
    }

    @Test public void missingAodMembersKeepNative() throws Exception {
        assertFalse(hooks.pluginLoaded(instance(NowBarHooks.AOD_PACKAGE, NowBarHooks.AOD_COMPONENT, plugin(true))));
        assertTrue(installs.isEmpty());
        NowBarHooks.Abi none = new NowBarHooks.Abi(new ClassLoader(null) { });
        assertFalse(none.usable());
    }

    @Test public void abiIsResolvedWithExactSignatures() {
        assertTrue(abi.usable());
        assertNotNull(abi.d0); assertNotNull(abi.e0); assertNotNull(abi.n); assertNotNull(abi.u);
        assertNotNull(abi.viewMode); assertNotNull(abi.expandedColor); assertNotNull(abi.stackRefresh);
        assertNotNull(abi.expandedState); assertNotNull(abi.gradientVisible);
        assertEquals(a.class, abi.n.getDeclaringClass());
    }

    @Test public void ongoingCardTakesGlassAndKeepsItAcrossBaseWriters() {
        NowBarOngoingCardView card = new NowBarOngoingCardView(activity);
        NowBarItem item = new NowBarItem("com.sec.android.app.clockpackage", null);
        card.d0(item);
        hooks.cardBound(abi, card, item);
        View container = card.getCardViewContainer();
        LiveGlassDrawable g = (LiveGlassDrawable) container.getBackground();
        card.N(item, container, 1f, 0xff00ff00);
        hooks.cardBound(abi, card, item);
        assertSame(g, container.getBackground());
        assertEquals(0x0000ff00, g.currentTint() & 0x00ffffff);
    }

    @Test public void mediaCardAndMediaItemStayNative() {
        NowBarMediaCardView media = new NowBarMediaCardView(activity);
        NowBarItem item = new NowBarItem("com.spotify.music", null);
        media.N(item, media.getCardViewContainer(), 1f, 0xff000000);
        hooks.cardBound(abi, media, item);
        assertFalse(media.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);
        assertFalse(glass.tracked(media.getCardViewContainer()));

        NowBarOngoingCardView card = new NowBarOngoingCardView(activity);
        NowBarItem withMedia = new NowBarItem("com.example", new Object());
        card.d0(withMedia);
        hooks.cardBound(abi, card, withMedia);
        assertFalse(card.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);

        NowBarOngoingCardView brief = new NowBarOngoingCardView(activity);
        NowBarItem briefing = new NowBarItem("com.samsung.android.app.moments", null);
        brief.d0(briefing);
        hooks.cardBound(abi, brief, briefing);
        assertFalse(brief.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void mediaPayloadArrivingLaterRestoresNative() {
        NowBarOngoingCardView card = new NowBarOngoingCardView(activity);
        NowBarItem item = new NowBarItem("com.example.timer", null);
        card.d0(item);
        hooks.cardBound(abi, card, item);
        assertTrue(card.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);
        item.media = new Object();
        card.d0(item);
        hooks.cardBound(abi, card, item);
        assertFalse(card.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void stackRefreshReassertsOnlyItsOngoingCards() {
        NowBarCardStackView stack = new NowBarCardStackView(activity);
        NowBarOngoingCardView card = new NowBarOngoingCardView(activity);
        NowBarMediaCardView media = new NowBarMediaCardView(activity);
        stack.addView(card);
        stack.addView(media);
        NowBarItem item = new NowBarItem("com.example.timer", null);
        card.d0(item);
        hooks.cardBound(abi, card, item);
        View container = card.getCardViewContainer();
        LiveGlassDrawable g = (LiveGlassDrawable) container.getBackground();
        card.N(item, container, 0f, 0xff222222); // refreshAllViews rewrote it
        media.N(item, media.getCardViewContainer(), 0f, 0xff222222);
        hooks.stackChanged(stack);
        assertSame(g, container.getBackground());
        assertFalse(media.getCardViewContainer().getBackground() instanceof LiveGlassDrawable);
    }

    private NowBarOngoingCardView layeredCard() {
        a.containerFactory = FakeCardView::new;
        NowBarOngoingCardView card = new NowBarOngoingCardView(activity);
        FrameLayout root = new FrameLayout(activity);
        root.addView(card, new FrameLayout.LayoutParams(600, 120));
        activity.setContentView(root);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return card;
    }
    private static GlassLayer layerOf(View container) {
        android.view.ViewGroup g = (android.view.ViewGroup) container;
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof GlassLayer) return (GlassLayer) g.getChildAt(i);
        return null;
    }
    private static com.samsung.android.app.facewidget.presentation.NowBarCardGradientView gradient(NowBarOngoingCardView card) {
        return (com.samsung.android.app.facewidget.presentation.NowBarCardGradientView) card.getGradientBgView();
    }

    /** DZIF: container and blur container are the same CardView; the glass goes on a layer inside it. */
    @Test public void cardViewContainerGetsAGlassLayerAndRestoresCardColour() {
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        assertSame(container, card.getBlurContainer());
        int nativeColor = container.getCardBackgroundColor().getDefaultColor();
        View content = container.getChildAt(0);
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        GlassLayer layer = layerOf(container);
        assertNotNull(layer);
        assertSame("Samsung's content stays the first child", content, container.getChildAt(0));
        assertTrue("drawn behind the content", layer.getTranslationZ() < 0f);
        assertFalse(layer.isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, layer.getImportantForAccessibility());
        LiveGlassDrawable g = (LiveGlassDrawable) layer.getBackground();
        assertTrue(g.showsGlass());
        assertEquals(1, env.regions.size());
        assertEquals("CardView transparent while the glass is drawn", 0, container.getCardBackgroundColor().getDefaultColor());
        assertEquals(0f, container.getCardElevation(), 0f);
        assertEquals(nativeColor & 0x00ffffff, g.currentTint() & 0x00ffffff);
        assertFalse("gradient hidden through its own setter", gradient(card).gradientVisible);
        // Rebinds and Samsung's own card writer: one layer, colour put back to transparent.
        container.setCardBackgroundColor(0xff2266ee);
        container.setRadius(24f);
        card.d0(new NowBarItem("com.example.timer", null));
        hooks.cardBound(abi, card, null);
        assertSame(layer, layerOf(container));
        assertEquals(0, container.getCardBackgroundColor().getDefaultColor());
        assertEquals("rebind recolours the already-installed glass", 0x002266ee, g.currentTint() & 0x00ffffff);
        g.setBounds(0, 0, 300, 120);
        android.graphics.Outline newOutline = new android.graphics.Outline();
        g.getOutline(newOutline);
        assertEquals("rebind refreshes the source CardView radius", 24f, newOutline.getRadius(), 0f);
        int layers = 0;
        for (int i = 0; i < container.getChildCount(); i++) if (container.getChildAt(i) instanceof GlassLayer) layers++;
        assertEquals(1, layers);
        // Off: layer removed, CardView colour/elevation and gradient as Samsung left them.
        env.on.remove(LiveSurface.NOWBAR);
        glass.refresh(LiveSurface.NOWBAR);
        hooks.refreshAll();
        assertNull(layerOf(container));
        assertEquals(0xff2266ee, container.getCardBackgroundColor().getDefaultColor());
        assertEquals(6f, container.getCardElevation(), 0f);
        assertTrue(gradient(card).gradientVisible);
    }

    /** The Now Bar rebinds dozens of times per second: the CardView is written once, not per call. */
    @Test public void repeatedBindsDoNotRewriteTheCardView() {
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        NowBarItem item = new NowBarItem("com.example.timer", null);
        hooks.cardBound(abi, card, item);
        int colors = container.colorWrites, elevations = container.elevationWrites;
        for (int i = 0; i < 60; i++) hooks.cardBound(abi, card, item);
        assertEquals(colors, container.colorWrites);
        assertEquals(elevations, container.elevationWrites);
        assertEquals(1, env.regions.size());
    }

    /** Only the minimum contract (class, media veto, container, bind writer): no optional AOD member is needed. */
    @Test public void worksWithoutTheOptionalAodMembers() throws Exception {
        for (String name : new String[]{"n", "u", "viewMode", "expandedColor", "stackRefresh", "expandedState", "gradientVisible", "gradientView", "blurContainer"}) {
            java.lang.reflect.Field f = NowBarHooks.Abi.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(abi, null);
        }
        assertTrue(abi.usable());
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        int nativeColor = container.getCardBackgroundColor().getDefaultColor();
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        LiveGlassDrawable g = (LiveGlassDrawable) layerOf(container).getBackground();
        assertTrue(g.showsGlass());
        assertTrue("gradient left alone without its setter", gradient(card).gradientVisible);
        env.on.remove(LiveSurface.NOWBAR);
        glass.refresh(LiveSurface.NOWBAR);
        hooks.refreshAll();
        assertNull(layerOf(container));
        assertEquals(nativeColor, container.getCardBackgroundColor().getDefaultColor());
    }

    @Test public void layerCoversTheWholeCardViewFrame() {
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        container.setPadding(12, 8, 12, 8);
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        card.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        card.layout(0, 0, 600, 120);
        GlassLayer layer = layerOf(container);
        assertEquals(0, layer.getLeft());
        assertEquals(container.getWidth(), layer.getWidth());
        assertEquals(container.getHeight(), layer.getHeight());
    }

    @Test public void blurUnavailableGivesTheCardViewItsColourBack() {
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        int nativeColor = container.getCardBackgroundColor().getDefaultColor();
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        LiveGlassDrawable g = (LiveGlassDrawable) layerOf(container).getBackground();
        env.blurEnabled = false; // battery saver
        glass.syncAll();
        assertFalse(g.showsGlass());
        assertEquals(nativeColor, container.getCardBackgroundColor().getDefaultColor());
        assertTrue(gradient(card).gradientVisible);
        env.blurEnabled = true;
        glass.syncAll();
        assertTrue(g.showsGlass());
        assertEquals(0, container.getCardBackgroundColor().getDefaultColor());
    }

    @Test public void regionFailureInDrawRestoresTheCardView() {
        env.failUpdate = true;
        NowBarOngoingCardView card = layeredCard();
        FakeCardView container = (FakeCardView) card.getCardViewContainer();
        int nativeColor = container.getCardBackgroundColor().getDefaultColor();
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        LiveGlassDrawable g = (LiveGlassDrawable) layerOf(container).getBackground();
        assertEquals(0, container.getCardBackgroundColor().getDefaultColor());
        g.setBounds(0, 0, 600, 120);
        g.draw(new TestCanvas(600, 120));
        assertFalse(g.showsGlass());
        assertEquals(nativeColor, container.getCardBackgroundColor().getDefaultColor());
        glass.syncAll();
        assertFalse("no rebuild loop after a draw failure", g.showsGlass());
        assertEquals(1, env.regions.size());
    }

    @Test public void unseenGradientIsRestoredToItsOwnState() {
        NowBarOngoingCardView card = layeredCard();
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        assertFalse(gradient(card).gradientVisible);
        env.on.remove(LiveSurface.NOWBAR);
        glass.refresh(LiveSurface.NOWBAR);
        assertTrue(gradient(card).gradientVisible);
    }

    @Test public void dozeKeepsTheNowBarNative() {
        NowBarOngoingCardView card = layeredCard();
        env.glassAllowed = false;
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        LiveGlassDrawable g = (LiveGlassDrawable) layerOf(card.getCardViewContainer()).getBackground();
        assertFalse(g.showsGlass());
        assertTrue(env.regions.isEmpty());
        assertNotEquals(0, ((FakeCardView) card.getCardViewContainer()).getCardBackgroundColor().getDefaultColor());
    }

    /** Ineligible or surface off: no layer is ever added to Samsung's CardView. */
    @Test public void noLayerForMediaOrWithTheSurfaceOff() {
        NowBarOngoingCardView card = layeredCard();
        hooks.cardBound(abi, card, new NowBarItem("com.example", new Object()));
        assertNull(layerOf(card.getCardViewContainer()));
        env.on.remove(LiveSurface.NOWBAR);
        hooks.cardBound(abi, card, new NowBarItem("com.example.timer", null));
        assertNull(layerOf(card.getCardViewContainer()));
        env.on.add(LiveSurface.NOWBAR);
        hooks.refreshAll();
        assertNotNull("config on: the known card gets its layer without waiting for a writer", layerOf(card.getCardViewContainer()));
    }
}

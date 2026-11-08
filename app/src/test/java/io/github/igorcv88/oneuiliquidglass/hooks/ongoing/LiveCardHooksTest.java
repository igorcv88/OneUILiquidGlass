package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
import java.time.Duration;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardItemView;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingCardAdapter;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * L2 on DZIF's real layering: CardItemView root without background, opaque GradientDrawable fill
 * below it, Samsung's blur region on an ancestor frame. One owned material region stays through
 * expansion; the competing native region is suspended and restored. Media uses the same shape gate.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveCardHooksTest {
    private Env env;
    private LiveGlass glass;
    private LiveCardHooks hooks;
    private OngoingCardAdapter adapter;
    private CardStackView stack;

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        hooks = new LiveCardHooks(glass, env);
        adapter = new OngoingCardAdapter();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        stack = new CardStackView(a);
        FrameLayout window = new FrameLayout(a);
        window.addView(stack);
        a.setContentView(window);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        OngoingActivityDataHelper.reset();
    }
    @After public void tearDown() { OngoingActivityDataHelper.reset(); }

    @Test public void cardClockLeaseCannotChangeGradusOrRegularApps() {
        assertTrue(LiveCardHooks.shouldTreatCardIcon("com.sec.android.app.clockpackage", 1));
        assertFalse(LiveCardHooks.shouldTreatCardIcon("com.sec.android.app.clockpackage", 0));
        assertFalse(LiveCardHooks.shouldTreatCardIcon("com.example.gradus", 1));
        assertFalse(LiveCardHooks.shouldTreatCardIcon("com.example.gradus", 3));
        assertFalse(LiveCardHooks.shouldTreatCardIcon(null, 1));
    }

    /** Samsung's sequence for one card: getView, wrap in ripple/blur frames, setBlurBG. */
    private CardItemView card(int position) {
        CardItemView root = (CardItemView) adapter.getView(position, null, stack);
        hooks.cardBound(adapter, root, position);
        FrameLayout blur = stack.addCard(root);
        stack.setBlurBG(blur, position);
        layoutNativeRegion(blur);
        hooks.cardWriter(blur, false);
        return root;
    }
    /** Simulates a measured and visible native region, including visibility aggregation. */
    private static void layoutNativeRegion(FrameLayout frame) {
        frame.measure(View.MeasureSpec.makeMeasureSpec(1340, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 1340, 288);
        frame.getBackground().setBounds(0, 0, 1340, 288);
        // The runner reported isVisible=false even with every ancestor VISIBLE. This fixture
        // simulates Samsung's active region, so establish its drawable visibility explicitly.
        // Do not override isVisible(): the hidden-region regression must still exercise it.
        frame.getBackground().setVisible(true, false);
    }
    private static boolean nativeBlur(Drawable d) { return d instanceof CardStackView.NativeBackgroundBlurDrawable; }

    /** Test failure context only: never logged by SystemUI or included in the APK. */
    private String nativeRegionState(CardItemView root) {
        View host = root.fill();
        View layer = (View) root.getParent();
        Drawable d = layer.getBackground();
        StringBuilder state = new StringBuilder("nativeRegion=")
                .append(LiveCardHooks.activeRegion(layer, host))
                .append(" modeRegionless=").append(glass.glassOf(host).regionless())
                .append(" attached(layer/host)=").append(layer.isAttachedToWindow()).append('/').append(host.isAttachedToWindow())
                .append(" visible=").append(d != null && d.isVisible())
                .append(" radiusField=").append(io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mBlurRadius"))
                .append(" radiusGetter=").append(NativeShape.call(d, "getBlurRadius"))
                .append(" alphaField=").append(io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mAlpha"))
                .append(" drawableAlpha=").append(d == null ? null : d.getAlpha())
                .append(" position=").append(io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(d, "mRect"))
                .append(" bounds=").append(d == null ? null : d.getBounds())
                .append(" hostSize=").append(host.getWidth()).append('x').append(host.getHeight());
        android.graphics.Rect mapped = new android.graphics.Rect(0, 0, host.getWidth(), host.getHeight());
        if (layer instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) layer).offsetDescendantRectToMyCoords(host, mapped);
            state.append(" mappedHost=").append(mapped);
        }
        Object states = io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(glass, "states");
        if (states instanceof java.util.Map) {
            Object tracked = ((java.util.Map<?, ?>) states).get(host);
            state.append(" available=").append(io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(tracked, "available"))
                    .append(" broken=").append(io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(tracked, "regionBroken"));
        }
        Object p = host;
        for (int i = 0; i < 32 && p instanceof View; i++) {
            View v = (View) p;
            state.append(" ancestor[").append(v.getClass().getSimpleName()).append("]=")
                    .append("visibility:").append(v.getVisibility()).append(",alpha:").append(v.getAlpha())
                    .append(",layer:").append(v.getLayerType());
            p = v.getParent();
        }
        return state.toString();
    }

    /** Actual adapter result observed in OULG_STACK_DETAIL_2: item_root encloses pip. */
    private FrameLayout boundOuterCard(int position) {
        CardItemView content = (CardItemView) adapter.getView(position, null, stack);
        FrameLayout outer = new FrameLayout(stack.getContext());
        android.content.res.Resources original = stack.getResources();
        android.content.res.Resources named = new android.content.res.Resources(
                original.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
            @Override public String getResourceEntryName(int id) {
                return id == 0x123456 ? "stack_pip_layout" : super.getResourceEntryName(id);
            }
        };
        FrameLayout pip = new FrameLayout(stack.getContext()) {
            @Override public android.content.res.Resources getResources() { return named; }
        };
        pip.setId(0x123456);
        pip.setClipToOutline(true);
        GradientDrawable white = new GradientDrawable(); white.setColor(0xffffffff);
        white.setCornerRadius(48f);
        GradientDrawable grey = new GradientDrawable(); grey.setColor(0xfa3a3a3d);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0), white, null);
        ripple.addLayer(grey);
        pip.setBackground(ripple);
        FrameLayout blur = new CardStackView.BlurFrame(stack.getContext());
        blur.addView(content, new FrameLayout.LayoutParams(-1, -1));
        pip.addView(blur, new FrameLayout.LayoutParams(-1, -1));
        outer.addView(pip, new FrameLayout.LayoutParams(-1, -1));
        stack.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        stack.setBlurBG(blur, position);
        layoutNativeRegion(blur);
        hooks.cardBound(adapter, outer, position);
        hooks.cardWriter(content.fill(), true);
        return outer;
    }

    @Test public void outerAdapterRootMutesBothDecorationsThroughSwipesAndRestoresThem() {
        checkOuterCards(false);
    }

    @Test public void liveAndMediaCardsKeepTheirGlassThroughSwipes() {
        checkOuterCards(true);
    }

    @Test public void oneOuterCardAlsoMutesItsOwnFrame() {
        adapter.mList.add(new OngoingActivityData("com.sec.android.app.clockpackage", false));
        FrameLayout outer = boundOuterCard(0);
        hooks.syncStackFills(stack);
        RippleDrawable ripple = (RippleDrawable) outer.getChildAt(0).getBackground();
        assertNotNull(ripple.getDrawable(0).getColorFilter());
        assertNotNull(ripple.getDrawable(1).getColorFilter());
        assertTrue(glass.glassOf(hooks.fills(outer).get(0)).showsGlass());
    }

    private void checkOuterCards(boolean media) {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.sec.android.app.clockpackage", media));
        FrameLayout first = boundOuterCard(0), second = boundOuterCard(1);
        for (int swipe = 0; swipe < 6; swipe++) {
            stack.bringChildToFront(swipe % 2 == 0 ? first : second);
            hooks.stackUpdated(stack);
            first.getViewTreeObserver().dispatchOnPreDraw();
            for (FrameLayout outer : new FrameLayout[]{first, second}) {
                FrameLayout pip = (FrameLayout) outer.getChildAt(0);
                RippleDrawable ripple = (RippleDrawable) pip.getBackground();
                assertNotNull("white fill must be muted below item_root", ripple.getDrawable(0).getColorFilter());
                assertNotNull("grey fill must be muted below item_root", ripple.getDrawable(1).getColorFilter());
                assertTrue(pip.getClipToOutline());
                View fill = hooks.fills(outer).get(0);
                LiveGlassDrawable material = glass.glassOf(fill);
                assertNotNull(material);
                assertTrue(material.showsGlass());
                material.setBounds(0, 0, 1340, 288);
                FakeBlurDrawable region = (FakeBlurDrawable) material.blur().drawable;
                int before = region.draws;
                material.draw(new TestCanvas(1340, 288));
                assertEquals("incoming and outgoing cards both submit glass", before + 1, region.draws);
            }
        }
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        hooks.syncStackFills(stack);
        for (FrameLayout outer : new FrameLayout[]{first, second}) {
            RippleDrawable ripple = (RippleDrawable) outer.getChildAt(0).getBackground();
            assertNull(ripple.getDrawable(0).getColorFilter());
            assertNull(ripple.getDrawable(1).getColorFilter());
        }
    }

    @Test public void rapidCardDetachReattachNeverRestoresOpaqueNativeBlurMidSwipe() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View blurFrame = (View) root.getParent();
        CardStackView.NativeBackgroundBlurDrawable nativeRegion =
                (CardStackView.NativeBackgroundBlurDrawable) blurFrame.getBackground();
        assertEquals(0, nativeRegion.getAlpha());
        ViewGroup window = (ViewGroup) stack.getParent();

        for (int swipe = 0; swipe < 4; swipe++) {
            window.removeView(stack);
            // A short detach must not restore Samsung's opaque grey backing.
            assertEquals("native region must stay muted during a brief detach", 0, nativeRegion.getAlpha());
            window.addView(stack);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
            assertEquals("reattached live card retains the glass companion", 0, nativeRegion.getAlpha());
            assertTrue(((LiveGlassDrawable) root.fill().getBackground()).showsGlass());
        }

        window.removeView(stack);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
        assertEquals("a genuinely detached card must restore its native region",
                255, nativeRegion.getAlpha());
    }

    @Test public void explicitCardGateOffRestoresNativeImmediatelyDespiteDetachGrace() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        CardStackView.NativeBackgroundBlurDrawable region =
                (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        ViewGroup window = (ViewGroup) stack.getParent();
        window.removeView(stack);
        assertEquals(0, region.getAlpha());
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertEquals("explicit gate-off must never defer native fallback", 255, region.getAlpha());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64));
        assertEquals(255, region.getAlpha());
    }

    @Test public void expansionKeepsOneRegionDespiteNativeGeometryAndRadiusChanges() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View fill = root.fill();
        LiveGlassDrawable g = (LiveGlassDrawable) fill.getBackground();
        Object region = g.blur();
        int created = env.regions.size();
        CardStackView.NativeBackgroundBlurDrawable nativeRegion =
                (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        for (int width : new int[]{185, 363, 659, 1039, 1353, 1340, 659, 185}) {
            fill.layout(0, 0, width, 288);
            nativeRegion.setBounds(0, 0, Math.max(1, width - 50), 288);
            nativeRegion.setBlurRadius(width % 2 == 0 ? 0 : 80);
            nativeRegion.setAlpha(255);
            fill.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse(g.regionless());
            assertSame(region, g.blur());
            assertEquals(0, nativeRegion.getAlpha());
            assertEquals(created, env.regions.size());
        }
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertEquals(255, nativeRegion.getAlpha());
        assertEquals(0xe6141428, nativeRegion.color());
    }

    @Test public void heldSemInfoWithoutAnActiveDrawableDoesNotProveBlur() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View layer = (View) root.getParent();
        env.samsungBlur(layer); // held info + a class name, no measurable active radius
        hooks.cardWriter(layer, false);
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        assertFalse(g.regionless());
        assertNotNull(g.blur());
    }

    @Test public void nativeVisibilityNeverChangesMaterialOwnership() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        Drawable nativeRegion = ((View) root.getParent()).getBackground();
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        Object own = g.blur();
        for (boolean visible : new boolean[]{false, true, false, true}) {
            nativeRegion.setVisible(visible, false);
            root.fill().getViewTreeObserver().dispatchOnPreDraw();
            assertSame(own, g.blur());
            assertFalse(g.regionless());
            assertEquals(visible, nativeRegion.isVisible());
        }
    }

    @Test public void failedOwnRegionRestoresAncestorsWithoutRetryingEveryFrame() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        env.failUpdate = true;
        CardItemView root = card(0);
        View fill = root.fill();
        LiveGlassDrawable g = (LiveGlassDrawable) fill.getBackground();
        CardStackView.NativeBackgroundBlurDrawable nativeRegion =
                (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        g.setBounds(0, 0, 1340, 288);
        g.draw(new TestCanvas(1340, 288));
        assertFalse(g.showsGlass());
        assertEquals(255, nativeRegion.getAlpha());
        assertEquals(0xe6141428, nativeRegion.color());
        int count = env.regions.size();
        for (int i = 0; i < 10; i++) fill.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(count, env.regions.size());
        root.removeView(fill);
        assertFalse(g.showsGlass());
    }

    @Test public void additionalAncestorFillIsSuppressedAndRestoredWithoutTouchingContent() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        GradientDrawable extra = new GradientDrawable();
        extra.setColor(0xff112233);
        extra.setCornerRadius(56f);
        extra.setPadding(7, 3, 9, 5);
        root.setBackground(extra);
        View content = new View(root.getContext());
        GradientDrawable contentBg = new GradientDrawable();
        contentBg.setColor(0xffaabbcc);
        content.setBackground(contentBg);
        root.fill().addView(content);
        hooks.cardWriter(root, false);
        assertEquals("only one material region per card", 1, glass.hosts(LiveSurface.CARD).size());
        assertNotSame(extra, root.getBackground());
        android.graphics.Rect padding = new android.graphics.Rect();
        root.getBackground().getPadding(padding);
        assertEquals(new android.graphics.Rect(7, 3, 9, 5), padding);
        assertSame(contentBg, content.getBackground());
        GradientDrawable rewritten = new GradientDrawable();
        rewritten.setColor(0xff445566);
        root.setBackground(rewritten);
        root.fill().getViewTreeObserver().dispatchOnPreDraw();
        assertNotSame("a later Samsung fill is suppressed too", rewritten, root.getBackground());
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertSame("restore the latest native write", rewritten, root.getBackground());
        assertEquals(255, rewritten.getAlpha());
        assertSame(contentBg, content.getBackground());
    }

    @Test public void replacedNativeRegionGetsItsExactColourBack() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View layer = (View) root.getParent();
        CardStackView.NativeBackgroundBlurDrawable old = (CardStackView.NativeBackgroundBlurDrawable) layer.getBackground();
        assertEquals(0x00141428, old.color());
        stack.setBlurBG(layer, 0);
        hooks.cardWriter(layer, false);
        assertEquals("detached drawable may be reused by Samsung", 0xe6141428, old.color());
        assertEquals(0x00141428, ((CardStackView.NativeBackgroundBlurDrawable) layer.getBackground()).color());
    }

    @Test public void fillOwnsMaterialWhileSamsungRegionIsSuspended() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        assertNull("the root keeps no background", root.getBackground());
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        assertFalse(nativeRegionState(root), g.regionless());
        assertTrue(g.showsGlass());
        assertNotNull(g.blur());
        assertEquals(0, ((View) root.getParent()).getBackground().getAlpha());
        View blurFrame = (View) root.getParent();
        assertTrue("Samsung's blur frame untouched", nativeBlur(blurFrame.getBackground()));
        assertTrue(((View) blurFrame.getParent()).getBackground() instanceof RippleDrawable);
        assertEquals(0x002050c0, g.currentTint() & 0x00ffffff);
    }

    /**
     * DZIF log 03/11: dozens of LIVE_GLASS_RESTORE reason=notFill in pairs. During expand/collapse
     * the fill's laid-out size differs from the root for a few frames; the fill must stay the same
     * host, wrapped once, through every writer.
     */
    @Test public void fillStaysStableThroughAnimationsAndWriters() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        root.layout(0, 0, 1340, 288);
        root.fill().layout(0, 0, 1340, 120); // mid-animation: sizes differ
        for (int i = 0; i < 20; i++) {
            stack.updateItemBg();
            hooks.stackUpdated(stack);
            hooks.cardBound(adapter, root, 0);
            stack.setBlurBG((View) root.getParent(), 0);
            hooks.cardWriter((View) root.getParent(), false);
        }
        assertTrue(glass.tracked(root.fill()));
        assertSame("never restored and re-wrapped", g, root.fill().getBackground());
        assertTrue(g.showsGlass());
        assertEquals(1, glass.hosts(LiveSurface.CARD).size());
    }

    /** Samsung's region is suspended; its original overlay returns with the native fallback. */
    @Test public void nativeRegionColourIsClearedWhileGlassShowsAndRestoredExactly() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        CardStackView.NativeBackgroundBlurDrawable region = (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        assertEquals("overlay transparent, RGB kept", 0x00141428, region.color());
        // Samsung re-installs its blur (new drawable, native colour): cleared again.
        stack.setBlurBG((View) root.getParent(), 0);
        hooks.cardWriter((View) root.getParent(), false);
        CardStackView.NativeBackgroundBlurDrawable again = (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        assertNotSame(region, again);
        assertEquals(0x00141428, again.color());
        // Battery Saver: no glass, the native overlay comes back.
        env.blurEnabled = false;
        glass.syncAll();
        assertEquals(0xe6141428, again.color());
        env.blurEnabled = true;
        glass.syncAll();
        assertEquals(0x00141428, again.color());
        // Off: exact restore of colour and fill.
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertEquals(0xe6141428, again.color());
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
    }

    /**
     * BackgroundBlurDrawable.setColor does not invalidate: the layer must be redrawn on each real
     * colour change (clear and restore), and not on rebinds that change nothing.
     */
    @Test public void regionColourChangesRedrawTheLayerOnlyWhenTheValueChanges() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        CardStackView.BlurFrame frame = (CardStackView.BlurFrame) root.getParent();
        CardStackView.NativeBackgroundBlurDrawable region = (CardStackView.NativeBackgroundBlurDrawable) frame.getBackground();
        assertEquals(0x00141428, region.color());
        int frameAfterClear = frame.invalidations, drawableAfterClear = region.invalidations;
        assertTrue("cleared colour redrawn", frameAfterClear >= 1 && drawableAfterClear >= 1);
        for (int i = 0; i < 30; i++) { hooks.cardBound(adapter, root, 0); hooks.stackUpdated(stack); }
        assertEquals("rebinds with nothing to change: no redraw", frameAfterClear, frame.invalidations);
        assertEquals(drawableAfterClear, region.invalidations);
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertEquals(0xe6141428, region.color());
        assertTrue("restored colour redrawn", frame.invalidations > frameAfterClear);
    }

    @Test public void compatibleMediaCardUsesItsOwnGlassRegion() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", false));
        CardItemView media = (CardItemView) adapter.getView(0, null, stack);
        adapter.mediaViews.add(media);
        hooks.cardBound(adapter, media, 0);
        FrameLayout blur = stack.addCard(media);
        stack.setBlurBG(blur, 0);
        hooks.cardWriter(blur, false);
        assertTrue(media.fill().getBackground() instanceof LiveGlassDrawable);
        assertEquals(0x00141428, ((CardStackView.NativeBackgroundBlurDrawable) blur.getBackground()).color());
    }

    @Test public void changeCardBgOnTheFillIsFollowed() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        stack.changeCardBg(root.fill(), 0xff0055ff);
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
        hooks.cardWriter(root.fill(), true);
        assertSame(g, root.fill().getBackground());
        assertEquals(0x000055ff, g.currentTint() & 0x00ffffff);
        stack.updateItemBg();
        hooks.stackUpdated(stack);
        assertSame(g, root.fill().getBackground());
    }

    @Test public void compatibleMediaViewInTheSameStackReceivesGlass() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", false));
        CardItemView rest = card(0);
        CardItemView media = (CardItemView) adapter.getView(1, null, stack);
        adapter.mediaViews.add(media); // Samsung's own verdict, even with the data flag false
        hooks.cardBound(adapter, media, 1);
        stack.setBlurBG(stack.addCard(media), 1);
        stack.updateItemBg();
        hooks.stackUpdated(stack);
        assertTrue(rest.fill().getBackground() instanceof LiveGlassDrawable);
        assertTrue(media.fill().getBackground() instanceof LiveGlassDrawable);
        stack.changeCardBg(media.fill(), 0xff000000);
        hooks.cardWriter(media.fill(), true);
        assertTrue(media.fill().getBackground() instanceof LiveGlassDrawable);
        assertTrue(glass.tracked(media.fill()));
    }

    @Test public void mediaFlagChangeDoesNotDiscardTheCompatibleFill() {
        OngoingActivityData d = new OngoingActivityData("com.samsung.android.health", false);
        adapter.mList.add(d);
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        d.mIsMediaOngoingData = true;
        stack.changeCardBg(root.fill(), 0xff0055ff);
        hooks.cardWriter(root.fill(), true);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        assertTrue(glass.tracked(root.fill()));
    }

    @Test public void withoutANativeBlurBehindTheFillTakesItsOwnRegion() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = (CardItemView) adapter.getView(0, null, stack);
        stack.addView(root); // no Samsung blur frame
        hooks.cardBound(adapter, root, 0);
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        assertFalse(g.regionless());
        assertTrue(g.showsGlass());
        assertEquals(1, env.regions.size());
        // Samsung adds its blur afterwards: suspend it and keep our material.
        FrameLayout blur = new FrameLayout(stack.getContext());
        stack.removeView(root);
        blur.addView(root);
        stack.addView(blur);
        stack.setBlurBG(blur, 0);
        layoutNativeRegion(blur);
        hooks.cardWriter(blur, false);
        assertFalse(nativeRegionState(root), g.regionless());
        assertNotNull(g.blur());
        assertEquals(0, blur.getBackground().getAlpha());
    }

    @Test public void batterySaverKeepsTheOpaqueNativeFill() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        env.blurEnabled = false;
        CardItemView root = card(0);
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        assertFalse("native fill drawn opaque", g.showsGlass());
        env.blurEnabled = true;
        glass.syncAll();
        assertTrue(g.showsGlass());
    }

    @Test public void mediaDataFlagAloneDoesNotExclude() {
        adapter.mList.add(new OngoingActivityData("com.example.player", true));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void mediaWithInteractiveNativeFillIsLeftAlone() {
        adapter.mList.add(new OngoingActivityData("com.example.player", true));
        CardItemView root = (CardItemView) adapter.getView(0, null, stack);
        RippleDrawable interactive = new RippleDrawable(ColorStateList.valueOf(0x66ffffff), new GradientDrawable(), null);
        root.fill().setBackground(interactive);
        hooks.cardBound(adapter, root, 0);
        assertSame("native controls must not be replaced by glass", interactive, root.fill().getBackground());
    }

    @Test public void noMediaMethodRequiredToRecognizeKnownFill() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void convertViewReusedForMediaKeepsGlass() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        View again = adapter.getView(1, root, stack);
        hooks.cardBound(adapter, again, 1);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        assertTrue(glass.tracked(root.fill()));
    }

    @Test public void helperIsTheFallbackDataSourceAndMediaUsesKnownFill() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        hooks.setHelper(OngoingDataSource.of(OngoingActivityDataHelper.class));
        CardItemView root = card(0); // mList is empty
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        CardItemView media = (CardItemView) adapter.getView(1, null, stack);
        adapter.mediaViews.add(media);
        hooks.cardBound(adapter, media, 1);
        assertTrue(media.fill().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void writerOnAnUnclassifiedViewDoesNothing() {
        View stray = new FrameLayout(stack.getContext());
        stack.changeCardBg(stray, 0xff123456);
        hooks.cardWriter(stray, true);
        assertTrue(stray.getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(stray));
    }

    @Test public void turningTheSurfaceOffRestoresTheExactFill() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        Drawable nativeFill = ((LiveGlassDrawable) root.fill().getBackground()).nativeDrawable();
        env.on.remove(LiveSurface.CARD);
        glass.refresh(LiveSurface.CARD);
        assertSame(nativeFill, root.fill().getBackground());
        assertEquals("translucency was draw-only", 255, nativeFill.getAlpha());
    }

    // ---- OULG_MEDIA_FALLBACK: RESTORED verdict=NO_DATA on an attached media card ----

    private static boolean glassed(CardItemView root) { return root.fill().getBackground() instanceof LiveGlassDrawable; }

    /** Track changes: Samsung swaps the list entry and fires writers before the next bind. */
    @Test public void writersBetweenAnEntrySwapAndTheRebindKeepTheGlass() {
        OngoingActivityData first = new OngoingActivityData("com.spotify.music", true);
        adapter.mList.add(first);
        CardItemView root = card(0);
        assertTrue(glassed(root));
        assertTrue("held while attached", hooks.holdsDataForTest(root));
        for (int track = 0; track < 4; track++) {
            adapter.mList.set(0, new OngoingActivityData("com.spotify.music", true));
            stack.updateItemBg();
            hooks.stackUpdated(stack);
            stack.changeCardBg(root.fill(), 0xff102030 + track);
            hooks.cardWriter(root.fill(), true);
            assertTrue("track " + track, glassed(root));
            hooks.cardBound(adapter, root, 0);
            assertTrue("rebound with the new entry, track " + track, glassed(root));
        }
    }

    @Test public void aLookupMissForTheSameStillListedItemKeepsTheGlass() {
        OngoingActivityData media = new OngoingActivityData("com.spotify.music", true);
        adapter.mList.add(media);
        CardItemView root = card(0);
        for (int i = 0; i < 4; i++) {
            // Mid-update: slot 0 momentarily unreadable, the same object still in the list.
            adapter.mList.set(0, null);
            adapter.mList.add(media);
            adapter.getView(0, root, stack);
            hooks.cardBound(adapter, root, 0);
            assertTrue("miss " + i, glassed(root));
            adapter.mList.clear();
            adapter.mList.add(media);
            hooks.cardBound(adapter, root, 0);
            assertTrue(glassed(root));
        }
    }

    @Test public void aLookupMissForAnItemNoLongerListedGoesNative() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardItemView root = card(0);
        adapter.mList.clear();
        hooks.cardBound(adapter, root, 0);
        assertFalse(glassed(root));
    }

    @Test public void aLookupMissAtAnotherPositionGoesNative() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardItemView root = card(0);
        adapter.getView(1, root, stack); // convertView reused for slot 1, which has no data
        hooks.cardBound(adapter, root, 1);
        assertFalse("unknown item in a reused view", glassed(root));
    }

    @Test public void aLookupMissFromAnotherAdapterGoesNative() {
        OngoingActivityData media = new OngoingActivityData("com.spotify.music", true);
        adapter.mList.add(media);
        CardItemView root = card(0);
        OngoingCardAdapter other = new OngoingCardAdapter();
        hooks.cardBound(other, root, 0);
        assertFalse(glassed(root));
    }

    @Test public void heldDataFlagsAreStillReadAndAutomationGoesNative() {
        OngoingActivityData media = new OngoingActivityData("com.spotify.music", true);
        adapter.mList.add(media);
        CardItemView root = card(0);
        media.mIsAutomation = true;
        stack.changeCardBg(root.fill(), 0xff222222);
        hooks.cardWriter(root.fill(), true);
        assertFalse(glassed(root));
        // A later lookup miss does not bring the glass back over an ineligible item.
        adapter.mList.set(0, null);
        adapter.mList.add(media);
        hooks.cardBound(adapter, root, 0);
        assertFalse(glassed(root));
    }

    @Test public void aConvertViewReusedForAnIneligibleItemGoesNative() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        OngoingActivityData automation = new OngoingActivityData("com.samsung.android.app.routines", false);
        automation.mIsAutomation = true;
        adapter.mList.add(automation);
        CardItemView root = card(0);
        assertTrue(glassed(root));
        adapter.getView(1, root, stack);
        hooks.cardBound(adapter, root, 1);
        assertFalse(glassed(root));
    }

    @Test public void theDataIsHeldOnlyWhileTheRootIsAttached() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardItemView root = card(0);
        assertTrue(hooks.holdsDataForTest(root));
        android.view.ViewGroup frame = (android.view.ViewGroup) root.getParent();
        frame.removeView(root);
        assertFalse("detached: weak only", hooks.holdsDataForTest(root));
        frame.addView(root);
        assertTrue("reattached while the data is alive", hooks.holdsDataForTest(root));
    }
}

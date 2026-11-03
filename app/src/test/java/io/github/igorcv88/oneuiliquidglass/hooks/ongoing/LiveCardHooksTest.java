package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Looper;
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
 * below it, Samsung's blur region on an ancestor frame. The fill becomes translucent over that
 * blur (no second region); Samsung's blur and ripple frames are never touched; media stays native.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveCardHooksTest {
    private Env env;
    private LiveGlass glass;
    private LiveCardHooks hooks;
    private OngoingCardAdapter adapter;
    private CardStackView stack;

    @Before public void setUp() throws Exception {
        env = new Env();
        glass = env.glass();
        hooks = new LiveCardHooks(glass, env);
        hooks.setIsMediaView(OngoingCardAdapter.class.getDeclaredMethod("isMediaView", View.class));
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
    /** A real frame measures before laying out its MATCH_PARENT descendants. */
    private static void layoutNativeRegion(FrameLayout frame) {
        frame.measure(View.MeasureSpec.makeMeasureSpec(1340, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 1340, 288);
        frame.getBackground().setBounds(0, 0, 1340, 288);
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

    @Test public void inactiveNativeRegionTakesAnOwnRegionAndSwitchesOnPreDraw() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View fill = root.fill();
        LiveGlassDrawable g = (LiveGlassDrawable) fill.getBackground();
        CardStackView.NativeBackgroundBlurDrawable nativeRegion =
                (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        assertTrue(nativeRegionState(root), g.regionless());
        nativeRegion.setBlurRadius(0); // no adapter writer: Samsung's expansion animation
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse("class exists, but its blur is inactive", g.regionless());
        assertNotNull(g.blur());
        assertTrue(g.showsGlass());
        FakeBlurDrawable own = env.regions.get(env.regions.size() - 1);
        g.setBounds(0, 0, 1340, 288);
        g.draw(new TestCanvas(1340, 288));
        assertEquals(LiveSurface.CARD.spec.blurPx, own.radius);
        nativeRegion.setBlurRadius(80);
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(g.regionless());
        assertNull(g.blur());
        assertEquals("own region released at the mode switch", 0, own.radius);
        nativeRegion.setAlpha(0);
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse(g.regionless());
        assertNotNull(g.blur());
        nativeRegion.setAlpha(255);
        nativeRegion.setBounds(0, 0, 20, 20);
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse("region must cover the fill during expansion", g.regionless());
        nativeRegion.setBounds(0, 0, 1340, 288);
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(g.regionless());
        nativeRegion.mRect.setEmpty();
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse("position lost on RenderThread", g.regionless());
        nativeRegion.mRect.set(0, 0, 1340, 288);
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertTrue(g.regionless());
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

    @Test public void detachAndFailedOwnRegionNeverLeaveARegionlessPane() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        View fill = root.fill();
        LiveGlassDrawable g = (LiveGlassDrawable) fill.getBackground();
        assertTrue("requires active native region before testing its loss: " + nativeRegionState(root), g.regionless());
        CardStackView.NativeBackgroundBlurDrawable nativeRegion =
                (CardStackView.NativeBackgroundBlurDrawable) ((View) root.getParent()).getBackground();
        nativeRegion.setBlurRadius(0);
        env.failCreate = true;
        fill.getViewTreeObserver().dispatchOnPreDraw();
        assertFalse(g.regionless());
        assertFalse(g.showsGlass());
        assertEquals("native overlay restored with fallback", 0xe6141428, nativeRegion.color());
        int count = env.regions.size();
        env.failCreate = false;
        for (int i = 0; i < 10; i++) fill.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("no create retry loop", count, env.regions.size());
        root.removeView(fill);
        assertFalse(g.regionless());
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

    @Test public void fillOverSamsungsBlurBecomesTranslucentWithoutASecondRegion() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        assertNull("the root keeps no background", root.getBackground());
        LiveGlassDrawable g = (LiveGlassDrawable) root.fill().getBackground();
        assertTrue(nativeRegionState(root), g.regionless());
        assertTrue(g.showsGlass());
        assertNull("no region of ours: Samsung's blur is the region", g.blur());
        for (FakeBlurDrawable r : env.regions) assertEquals("any earlier region released", 0, r.radius);
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

    /** Samsung's region keeps its blur; only its dark colour overlay is cleared while glass shows. */
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

    @Test public void mediaCardRegionColourIsNeverTouched() {
        adapter.mList.add(new OngoingActivityData("com.spotify.music", false));
        CardItemView media = (CardItemView) adapter.getView(0, null, stack);
        adapter.mediaViews.add(media);
        hooks.cardBound(adapter, media, 0);
        FrameLayout blur = stack.addCard(media);
        stack.setBlurBG(blur, 0);
        hooks.cardWriter(blur, false);
        assertEquals(0xe6141428, ((CardStackView.NativeBackgroundBlurDrawable) blur.getBackground()).color());
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

    @Test public void mediaViewInTheSameStackIsNeverTouched() {
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
        assertTrue(media.fill().getBackground() instanceof GradientDrawable);
        stack.changeCardBg(media.fill(), 0xff000000);
        hooks.cardWriter(media.fill(), true);
        assertTrue(media.fill().getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(media.fill()));
    }

    @Test public void writerRereadsTheDataFlags() {
        OngoingActivityData d = new OngoingActivityData("com.samsung.android.health", false);
        adapter.mList.add(d);
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        d.mIsMediaOngoingData = true;
        stack.changeCardBg(root.fill(), 0xff0055ff);
        hooks.cardWriter(root.fill(), true);
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(root.fill()));
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
        // Samsung adds its blur afterwards (setBlurBG on an ancestor): ours is dropped, no double blur.
        FrameLayout blur = new FrameLayout(stack.getContext());
        stack.removeView(root);
        blur.addView(root);
        stack.addView(blur);
        stack.setBlurBG(blur, 0);
        layoutNativeRegion(blur);
        hooks.cardWriter(blur, false);
        assertTrue(nativeRegionState(root), g.regionless());
        assertNull(g.blur());
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

    @Test public void mediaDataFlagAloneExcludes() {
        adapter.mList.add(new OngoingActivityData("com.example.player", true));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
    }

    @Test public void unknownNativeVerdictKeepsEverythingNative() {
        hooks.setIsMediaView(null);
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
    }

    @Test public void convertViewReusedForMediaIsRestored() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardItemView root = card(0);
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        View again = adapter.getView(1, root, stack);
        hooks.cardBound(adapter, again, 1);
        assertTrue(root.fill().getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(root.fill()));
    }

    @Test public void helperIsTheFallbackDataSourceAndNativeVerdictStillWins() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        hooks.setHelper(OngoingDataSource.of(OngoingActivityDataHelper.class));
        CardItemView root = card(0); // mList is empty
        assertTrue(root.fill().getBackground() instanceof LiveGlassDrawable);
        CardItemView media = (CardItemView) adapter.getView(1, null, stack);
        adapter.mediaViews.add(media);
        hooks.cardBound(adapter, media, 1);
        assertTrue(media.fill().getBackground() instanceof GradientDrawable);
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
}

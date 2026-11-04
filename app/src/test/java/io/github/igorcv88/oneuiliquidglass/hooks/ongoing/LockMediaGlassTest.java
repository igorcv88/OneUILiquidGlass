package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import com.samsung.android.app.facewidget.presentation.NowBarMediaCardView;
import com.samsung.android.app.facewidget.presentation.NowBarOngoingCardView;
import com.samsung.android.app.facewidget.utils.NowBarCardStackView;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Lockscreen media capsule: lens before the first frame, fill/stroke only while it is drawn. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LockMediaGlassTest {
    private Env env;
    private LiveGlass glass;
    private LockMediaGlass lens;
    private Activity activity;
    private NowBarCardStackView stack;
    private NowBarMediaCardView card;
    private FakeCardView capsule;
    private View fill, stroke, art, wave;
    private String container, fillName, strokeName;

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        lens = new LockMediaGlass(glass);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        NativeIds.clearForTest();
        container = LockMediaGlass.CONTAINER; fillName = LockMediaGlass.FILL; strokeName = LockMediaGlass.STROKE;
        LockMediaGlass.CONTAINER = "background";
        LockMediaGlass.FILL = "icon_frame";
        LockMediaGlass.STROKE = "widget_frame";
    }

    @After public void tearDown() {
        LockMediaGlass.CONTAINER = container; LockMediaGlass.FILL = fillName; LockMediaGlass.STROKE = strokeName;
    }

    /** stack > card > [capsule CardView, content > (fill, stroke, art, wave)], not yet attached. */
    private void build() {
        stack = new NowBarCardStackView(activity);
        card = new NowBarMediaCardView(activity);
        capsule = new FakeCardView(activity);
        capsule.setId(android.R.id.background);
        FrameLayout content = new FrameLayout(activity);
        fill = new View(activity);
        fill.setId(android.R.id.icon_frame);
        stroke = new View(activity);
        stroke.setId(android.R.id.widget_frame);
        art = new View(activity);
        wave = new View(activity);
        content.addView(fill);
        content.addView(stroke);
        content.addView(art);
        content.addView(wave);
        card.addView(capsule, new FrameLayout.LayoutParams(-1, -1));
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        stack.addView(card, new FrameLayout.LayoutParams(848, 224));
    }

    private void show() {
        FrameLayout root = new FrameLayout(activity);
        root.addView(stack, new FrameLayout.LayoutParams(848, 224));
        activity.setContentView(root);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void frame() { card.getViewTreeObserver().dispatchOnPreDraw(); }

    private static GlassLayer layerOf(View container) {
        android.view.ViewGroup g = (android.view.ViewGroup) container;
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof GlassLayer) return (GlassLayer) g.getChildAt(i);
        return null;
    }

    @Test public void lensIsInPlaceFromConstructionNotFromADelayedScan() {
        build();
        lens.watch(card);            // the constructor hook
        assertNull("nothing before attach", layerOf(capsule));
        show();                      // attach + first traversal
        GlassLayer layer = layerOf(capsule);
        assertNotNull("layer added on attach, laid out in the first frame", layer);
        assertTrue(layer.getBackground() instanceof LiveGlassDrawable);
        assertTrue(((LiveGlassDrawable) layer.getBackground()).showsGlass());
        assertEquals("capsule fill hidden with the lens", View.INVISIBLE, fill.getVisibility());
        assertEquals("Samsung's border hidden with the lens", View.INVISIBLE, stroke.getVisibility());
        assertEquals("art untouched", View.VISIBLE, art.getVisibility());
        assertEquals("wave untouched", View.VISIBLE, wave.getVisibility());
        assertEquals(1f, art.getAlpha(), 0f);
        assertTrue("layer behind the content", layer.getTranslationZ() < 0f);
    }

    @Test public void theCapsuleCardViewKeepsItsOwnBackgroundAndColour() {
        build();
        android.graphics.drawable.Drawable nativeBg = capsule.getBackground();
        int colour = capsule.getCardBackgroundColor().getDefaultColor();
        lens.watch(card);
        show();
        assertSame(nativeBg, capsule.getBackground());
        assertEquals(colour, capsule.getCardBackgroundColor().getDefaultColor());
        assertEquals(0, capsule.colorWrites);
        assertEquals(0, capsule.elevationWrites);
    }

    @Test public void withoutTheLensTheNativeCapsuleStays() {
        build();
        env.glassAllowed = false;    // doze: the Now Bar stays native
        lens.watch(card);
        show();
        frame();
        assertEquals(View.VISIBLE, fill.getVisibility());
        assertEquals(View.VISIBLE, stroke.getVisibility());
    }

    @Test public void expandedStaysNativeAndCompactGetsTheLensBack() {
        build();
        lens.watch(card);
        show();
        lens.expandedState(stack, true);
        assertNull(layerOf(capsule));
        assertEquals(View.VISIBLE, fill.getVisibility());
        assertEquals(View.VISIBLE, stroke.getVisibility());
        lens.expandedState(stack, false);
        frame();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(layerOf(capsule));
        assertEquals(View.INVISIBLE, fill.getVisibility());
    }

    @Test public void aCardTallerThanWideIsTreatedAsExpanded() {
        build();
        lens.watch(card);
        show();
        card.layout(0, 0, 1280, 1942);
        frame();
        assertNull(layerOf(capsule));
        assertEquals(View.VISIBLE, fill.getVisibility());
        assertEquals(View.VISIBLE, stroke.getVisibility());
    }

    @Test public void gateOffAndDetachRestoreEverything() {
        build();
        lens.watch(card);
        show();
        env.on.remove(LiveSurface.NOWBAR);
        lens.refreshAll();
        assertNull(layerOf(capsule));
        assertEquals(View.VISIBLE, fill.getVisibility());
        assertEquals(View.VISIBLE, stroke.getVisibility());
        env.on.add(LiveSurface.NOWBAR);
        lens.refreshAll();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.INVISIBLE, fill.getVisibility());
        ((FrameLayout) stack.getParent()).removeView(stack);
        assertNull(layerOf(capsule));
        assertEquals(View.VISIBLE, fill.getVisibility());
        assertEquals(View.VISIBLE, stroke.getVisibility());
    }

    @Test public void samsungShowingTheFillAgainIsHiddenAgainAndItsOwnGoneIsKept() {
        build();
        lens.watch(card);
        show();
        fill.setVisibility(View.VISIBLE);       // rebind
        frame();
        assertEquals(View.INVISIBLE, fill.getVisibility());
        stroke.setVisibility(View.GONE);         // Samsung's own decision while held
        env.on.remove(LiveSurface.NOWBAR);
        lens.refreshAll();
        assertEquals("not overwritten by the restore", View.GONE, stroke.getVisibility());
    }

    @Test public void onlyTheMediaCardIsFollowed() {
        NowBarOngoingCardView ongoing = new NowBarOngoingCardView(activity);
        lens.watch(ongoing);
        assertEquals(0, lens.tracked());
        build();
        lens.watch(card);
        lens.watch(card);
        assertEquals(1, lens.tracked());
    }

    @Test public void hideRestoresOnlyItsOwnInvisible() {
        View v = new View(activity);
        LockMediaGlass.Hide h = new LockMediaGlass.Hide();
        h.target = new java.lang.ref.WeakReference<>(v);
        assertTrue(h.sync(true));
        assertEquals(View.INVISIBLE, v.getVisibility());
        assertFalse(h.sync(true));
        assertTrue(h.sync(false));
        assertEquals(View.VISIBLE, v.getVisibility());
        v.setVisibility(View.GONE);
        assertFalse("a view Samsung hid is left alone", h.sync(true));
        assertEquals(View.GONE, v.getVisibility());
    }
}

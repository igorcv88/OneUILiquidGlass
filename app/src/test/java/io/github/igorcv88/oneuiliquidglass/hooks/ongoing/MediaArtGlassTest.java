package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Album art over glass: exact leases, native without glass, property read per session only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaArtGlassTest {
    /** Matched by class-name suffix, like SystemUI's row. */
    static final class ExpandableNotificationRow extends FrameLayout {
        ExpandableNotificationRow(Activity a) { super(a); }
    }

    private Activity activity;
    private final AtomicLong now = new AtomicLong(10_000);
    private final AtomicInteger reads = new AtomicInteger();
    private volatile String prop;
    private volatile String probeProp;
    private volatile boolean glass;
    private FrameLayout root, art;
    private ImageView origin, fresh;
    private View controls;
    private ColorDrawable fill;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        NativeIds.clearForTest();
        MediaArtGlass.resetForTest();
        MediaArtGlass.names = new MediaArtGlass.Names("widget_frame", "background", "icon_frame");
        MediaArtGlass.clock = now::get;
        MediaArtGlass.property = () -> { reads.incrementAndGet(); return prop; };
        MediaArtGlass.probeProperty = () -> probeProp;
        MediaArtGlass.rowGlass = row -> glass;
    }

    @After public void tearDown() { MediaArtGlass.resetForTest(); }

    /** row > root(media_carousel_layout) > art(shade_album_art: fill, originImage, newImage) + controls. */
    private void shade() {
        ExpandableNotificationRow row = new ExpandableNotificationRow(activity);
        root = new FrameLayout(activity);
        root.setId(android.R.id.widget_frame);
        art = new FrameLayout(activity);
        art.setId(android.R.id.background);
        fill = new ColorDrawable(0xff000000);
        art.setBackground(fill);
        origin = new ImageView(activity);
        fresh = new ImageView(activity);
        art.addView(origin);
        art.addView(fresh);
        controls = new View(activity);
        root.addView(art);
        root.addView(controls);
        row.addView(root);
        activity.setContentView(row);
    }

    private void frame() { root.getViewTreeObserver().dispatchOnPreDraw(); }

    @Test public void artShowsTheGlassAtTheDefaultOpacity() {
        shade();
        glass = true;
        MediaArtGlass m = MediaArtGlass.watch(root);
        assertEquals(MediaArtGlass.Context.SHADE, m.contextForTest());
        frame();
        assertEquals(0.80f, art.getTransitionAlpha(), 0f);
        assertEquals("Samsung's alpha is untouched", 1f, art.getAlpha(), 0f);
        assertEquals("the opaque fill no longer hides the glass", 0, fill.getAlpha());
        assertSame(fill, art.getBackground());
        // One group alpha for both images: neither carries its own.
        assertEquals(1f, origin.getAlpha(), 0f);
        assertEquals(1f, origin.getTransitionAlpha(), 0f);
        assertEquals(1f, fresh.getAlpha(), 0f);
        assertEquals(1f, fresh.getTransitionAlpha(), 0f);
        assertEquals("controls stay opaque", 1f, controls.getTransitionAlpha(), 0f);
    }

    @Test public void withoutGlassEverythingIsHandedBackExactly() {
        shade();
        glass = true;
        MediaArtGlass.watch(root);
        frame();
        glass = false;
        frame();
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
        assertEquals(0xff000000, fill.getColor());
        assertSame(fill, art.getBackground());
    }

    @Test public void neverAppliedWhenNoGlassIsBehind() {
        shade();
        glass = false;
        MediaArtGlass.watch(root);
        frame();
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
    }

    @Test public void samsungRecolouringIsClearedAgainAndRestoredWithItsColour() {
        shade();
        glass = true;
        MediaArtGlass.watch(root);
        frame();
        fill.setColor(0xff112233); // album change: setColor resets the alpha
        assertEquals(255, fill.getAlpha());
        frame();
        assertEquals(0, fill.getAlpha());
        glass = false;
        frame();
        assertEquals(0xff112233, fill.getColor());
    }

    @Test public void aReplacedFillIsLeasedAndTheOldOneHandedBack() {
        shade();
        glass = true;
        MediaArtGlass.watch(root);
        frame();
        ColorDrawable next = new ColorDrawable(0xff445566);
        art.setBackground(next);
        frame();
        assertEquals(255, fill.getAlpha());
        assertEquals(0, next.getAlpha());
    }

    @Test public void anUnknownFillKeepsTheMediaNative() {
        shade();
        GradientDrawable opaque = new GradientDrawable();
        opaque.setColor(0xff000000);
        art.setBackground(opaque);
        glass = true;
        MediaArtGlass.watch(root);
        frame();
        assertEquals("a lower alpha would only darken the art", 1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, opaque.getAlpha());
    }

    @Test public void propertyIsReadPerSessionNotPerFrame() {
        shade();
        glass = true;
        prop = "0.65";
        MediaArtGlass.watch(root);
        int afterAttach = reads.get();
        for (int i = 0; i < 30; i++) { now.addAndGet(16); frame(); }
        assertEquals("no read while frames keep coming", afterAttach, reads.get());
        assertEquals(0.65f, art.getTransitionAlpha(), 0f);
        prop = "off";
        now.addAndGet(MediaArtGlass.SESSION_GAP_MS + 1);
        frame();
        assertEquals(afterAttach + 1, reads.get());
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
    }

    @Test public void detachHandsEverythingBackAndKeepsNoChildOfTheRoot() {
        shade();
        glass = true;
        MediaArtGlass m = MediaArtGlass.watch(root);
        frame();
        assertSame(art, m.artForTest());
        ((FrameLayout) root.getParent()).removeView(root);
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
        assertNull("a strong child reference would keep the weak key alive", m.artForTest());
    }

    @Test public void aStackHolderWithoutLiveGlassStaysNative() {
        FrameLayout holder = new FrameLayout(activity);
        holder.setId(android.R.id.icon_frame);
        root = new FrameLayout(activity);
        root.setId(android.R.id.widget_frame);
        art = new FrameLayout(activity);
        art.setId(android.R.id.background);
        fill = new ColorDrawable(0xff000000);
        art.setBackground(fill);
        root.addView(art);
        holder.addView(root);
        activity.setContentView(holder);
        MediaArtGlass m = MediaArtGlass.watch(root);
        assertEquals(MediaArtGlass.Context.STACK, m.contextForTest());
        frame();
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
    }

    @Test public void otherInstancesOfTheClassAreNotFollowed() {
        shade();
        glass = true;
        MediaArtGlass.watch(art); // not media_carousel_layout
        art.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(1f, art.getTransitionAlpha(), 0f);
    }

    private View addOverlay(float alpha) {
        View overlay = new ImageView(activity);
        overlay.setId(android.R.id.content);
        overlay.setAlpha(alpha);
        root.addView(overlay, 1);
        return overlay;
    }

    @Test public void diagnosticProbeHidesAlbumArtWithoutGlassAndRestoresOnDetach() {
        shade();
        View overlay = addOverlay(0.65f);
        glass = false;  // The diagnostic must test the view even when eligibility flickers.
        probeProp = "1";
        MediaArtGlass.watch(root);
        frame();
        assertEquals(0f, art.getAlpha(), 0f);
        assertEquals(0.65f, overlay.getAlpha(), 0f);
        assertEquals(1f, art.getTransitionAlpha(), 0f);
        assertEquals(255, fill.getAlpha());
        ((FrameLayout) root.getParent()).removeView(root);
        assertEquals(1f, art.getAlpha(), 0f);
        assertEquals(0.65f, overlay.getAlpha(), 0f);
    }

    @Test public void diagnosticProbeCanHideOverlayOnlyAndRestoreOnPropertyChange() {
        shade();
        View overlay = addOverlay(0.65f);
        probeProp = "2";
        MediaArtGlass.watch(root);
        frame();
        assertEquals(1f, art.getAlpha(), 0f);
        assertEquals(0f, overlay.getAlpha(), 0f);
        probeProp = "off";
        now.addAndGet(MediaArtGlass.SESSION_GAP_MS + 1);
        frame();
        assertEquals(1f, art.getAlpha(), 0f);
        assertEquals(0.65f, overlay.getAlpha(), 0f);
    }

    @Test public void diagnosticProbeHidesBothLayersAndRestoresNativeAlphas() {
        shade();
        View overlay = addOverlay(0.45f);
        art.setAlpha(0.80f);
        probeProp = "3";
        MediaArtGlass.watch(root);
        frame();
        assertEquals(0f, art.getAlpha(), 0f);
        assertEquals(0f, overlay.getAlpha(), 0f);
        probeProp = "0";
        now.addAndGet(MediaArtGlass.SESSION_GAP_MS + 1);
        frame();
        assertEquals(0.80f, art.getAlpha(), 0f);
        assertEquals(0.45f, overlay.getAlpha(), 0f);
    }

    @Test public void probeParsing() {
        assertEquals(0, MediaArtGlass.parseProbe(null));
        assertEquals(0, MediaArtGlass.parseProbe("off"));
        assertEquals(1, MediaArtGlass.parseProbe("1"));
        assertEquals(2, MediaArtGlass.parseProbe(" 2 "));
        assertEquals(3, MediaArtGlass.parseProbe("3"));
        assertEquals(0, MediaArtGlass.parseProbe("4"));
    }

    @Test public void opacityParsing() {
        assertEquals(0.80f, MediaArtGlass.parse(null), 0f);
        assertEquals(0.80f, MediaArtGlass.parse(""), 0f);
        assertEquals(MediaArtGlass.OFF, MediaArtGlass.parse("0"), 0f);
        assertEquals(MediaArtGlass.OFF, MediaArtGlass.parse("off"), 0f);
        assertEquals(0.50f, MediaArtGlass.parse("0.3"), 0f);
        assertEquals(1.00f, MediaArtGlass.parse("1.4"), 0f);
        assertEquals(0.65f, MediaArtGlass.parse("0.65"), 0f);
        assertEquals(0.80f, MediaArtGlass.parse("abc"), 0f);
    }
}

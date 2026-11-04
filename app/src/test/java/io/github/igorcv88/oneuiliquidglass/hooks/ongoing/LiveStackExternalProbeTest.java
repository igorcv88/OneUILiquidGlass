package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** E1 must not change any view, its sibling's drawable, shadow or clipping. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveStackExternalProbeTest {
    @Test public void externalViewSnapshotIsPureAndDoesNotReadTextContent() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout indicator = new FrameLayout(activity);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xfa3a3a3d);
        fill.setCornerRadius(100f);
        indicator.setBackground(fill);
        indicator.setElevation(6f);
        indicator.setTranslationZ(2f);
        indicator.setClipToOutline(true);
        indicator.layout(0, 0, 1232, 200);

        String state = LiveStackExternalProbe.describe(indicator);
        assertTrue(state, state.contains("wh=1232x200"));
        assertTrue(state, state.contains("z=8.0"));
        assertTrue(state, state.contains("clipBounds=-"));
        assertTrue(state, state.contains("fill=fa3a3a3d"));
        assertSame(fill, indicator.getBackground());
        assertEquals(255, fill.getAlpha());
        assertEquals(6f, indicator.getElevation(), 0f);
        assertEquals(2f, indicator.getTranslationZ(), 0f);
        assertTrue(indicator.getClipToOutline());
    }

    @Test public void externalTraversalDoesNotTouchSiblingStackOrNativeFill() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout layout = new LinearLayout(activity);
        CardStackView stack = new CardStackView(activity);
        FrameLayout indicator = new FrameLayout(activity);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xff456789);
        fill.setCornerRadius(90f);
        indicator.setBackground(fill);
        View child = new View(activity);
        stack.addView(child);
        layout.addView(stack);
        layout.addView(indicator);
        activity.setContentView(layout);
        Map<Integer, String> previous = new HashMap<>();

        LiveStackExternalProbe.sample(stack, 1, 400L, previous);
        assertTrue("external sibling was sampled", previous.containsKey(System.identityHashCode(indicator)));
        assertSame(fill, indicator.getBackground());
        assertSame(child, stack.getChildAt(0));
        assertEquals(View.VISIBLE, indicator.getVisibility());
        assertEquals(255, fill.getAlpha());

        // A second stable read leaves the same visual properties intact.
        LiveStackExternalProbe.sample(stack, 1, 480L, previous);
        assertSame(fill, indicator.getBackground());
        assertEquals(0xff456789, fill.getColor().getDefaultColor());
        assertEquals(255, fill.getAlpha());
    }

    @Test public void unknownResourceNameIsSafe() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        assertEquals("-", LiveStackExternalProbe.resourceName(view));
    }
}

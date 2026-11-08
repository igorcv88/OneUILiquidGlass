package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Name-to-id lookup that replaced the per-frame reflective tree walk of the Now Bar card. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NativeIdsTest {
    private Activity activity;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        NativeIds.clearForTest();
    }

    private FrameLayout card() {
        FrameLayout card = new FrameLayout(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setId(android.R.id.widget_frame);
        ImageView icon = new ImageView(activity);
        icon.setId(android.R.id.icon);
        FakeCardView expanded = new FakeCardView(activity);
        expanded.setId(android.R.id.icon_frame);
        row.addView(icon);
        row.addView(expanded);
        card.addView(row);
        return card;
    }

    @Test public void findsNestedViewsByEntryName() {
        FrameLayout card = card();
        View icon = NativeIds.find(card, "icon");
        assertTrue(icon instanceof ImageView);
        assertEquals(android.R.id.icon, icon.getId());
        View frame = NativeIds.find(card, "icon_frame");
        assertTrue(frame instanceof FakeCardView);
    }

    @Test public void repeatedLookupsReturnTheCurrentInstance() {
        FrameLayout card = card();
        View first = NativeIds.find(card, "icon");
        // Samsung rebinds: the old view leaves, a new one with the same id arrives.
        LinearLayout row = (LinearLayout) card.getChildAt(0);
        row.removeView(first);
        ImageView replacement = new ImageView(activity);
        replacement.setId(android.R.id.icon);
        row.addView(replacement, 0);
        assertSame(replacement, NativeIds.find(card, "icon"));
    }

    @Test public void descendantInflationInvalidatesAThrottledMissImmediately() {
        FrameLayout expanded = new FrameLayout(activity);
        FrameLayout holder = new FrameLayout(activity);
        LinearLayout nested = new LinearLayout(activity);
        expanded.addView(holder);
        holder.addView(nested);
        int empty = NativeIds.childrenSignature(expanded);
        assertNull(NativeIds.find(expanded, "resource_not_present"));
        nested.addView(new ImageView(activity));
        assertNotEquals("new children must bypass the 750 ms negative cache",
                empty, NativeIds.childrenSignature(expanded));
    }

    @Test public void missingViewsAreNull() {
        FrameLayout card = card();
        assertNull(NativeIds.find(card, "title"));
        assertNull(NativeIds.find(card, "no_such_entry_name_anywhere"));
        assertNull(NativeIds.find(null, "icon"));
    }

    @Test public void cardLikeIsAnsweredPerClassAndStaysCorrect() {
        FakeCardView a = new FakeCardView(activity), b = new FakeCardView(activity);
        FrameLayout plain = new FrameLayout(activity);
        assertTrue(NativeShape.cardLike(a));
        assertTrue("cached per class", NativeShape.cardLike(b));
        assertFalse(NativeShape.cardLike(plain));
        assertFalse(NativeShape.cardLike(new FrameLayout(activity)));
        assertFalse(NativeShape.cardLike(null));
    }
}

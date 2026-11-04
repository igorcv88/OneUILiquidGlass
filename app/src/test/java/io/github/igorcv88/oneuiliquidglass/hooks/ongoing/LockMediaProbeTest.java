package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Native state reversibility for the diagnostic only; plugin resources are probed on device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LockMediaProbeTest {
    private Activity activity;

    @Before public void setup() { activity = Robolectric.buildActivity(Activity.class).setup().get(); }

    @Test public void nativeBackgroundRestoredWithoutTouchingCardOrArt() {
        FrameLayout card = new FrameLayout(activity);
        ColorDrawable nativeBackground = new ColorDrawable(Color.rgb(34, 56, 78));
        nativeBackground.setAlpha(183);
        card.setBackground(nativeBackground);
        card.setAlpha(0.76f);
        ImageView circularArt = new ImageView(activity);
        card.addView(circularArt);
        View waveform = new View(activity);
        card.addView(waveform);

        LockMediaProbe.Lease lease = new LockMediaProbe.Lease(card);
        assertTrue(lease.valid());
        assertEquals(183, lease.nativeAlpha());
        assertTrue(lease.hide());
        assertEquals(null, card.getBackground());
        assertEquals(183, nativeBackground.getAlpha());
        assertEquals(0.76f, card.getAlpha(), 0f);
        assertEquals(1f, circularArt.getAlpha(), 0f);
        assertEquals(1f, waveform.getAlpha(), 0f);
        assertTrue(lease.matches(card));

        lease.restore();
        assertEquals(183, nativeBackground.getAlpha());
        assertTrue(card.getBackground() == nativeBackground);
        lease.restore(); // Idempotent.
        assertEquals(183, nativeBackground.getAlpha());
    }

    @Test public void replacedBackgroundMakesOldLeaseStaleButRestorable() {
        View card = new View(activity);
        GradientDrawable original = new GradientDrawable();
        original.setAlpha(221);
        card.setBackground(original);
        LockMediaProbe.Lease old = new LockMediaProbe.Lease(card);
        assertTrue(old.hide());
        assertEquals(null, card.getBackground());
        ColorDrawable replacement = new ColorDrawable(Color.MAGENTA);
        replacement.setAlpha(147);
        card.setBackground(replacement);
        assertFalse(old.matches(card));
        old.restore();
        assertEquals(221, original.getAlpha());
        assertTrue(card.getBackground() == replacement);
        assertEquals(147, replacement.getAlpha());

        LockMediaProbe.Lease next = new LockMediaProbe.Lease(card);
        assertTrue(next.hide());
        assertEquals(null, card.getBackground());
        assertEquals(147, replacement.getAlpha());
        next.restore();
        assertEquals(147, replacement.getAlpha());
        assertTrue(card.getBackground() == replacement);
    }

    @Test public void nativeDrawableIsUntouchedWhileRemoved() {
        View card = new View(activity);
        ColorDrawable nativeBackground = new ColorDrawable(Color.BLUE);
        nativeBackground.setAlpha(190);
        card.setBackground(nativeBackground);
        LockMediaProbe.Lease lease = new LockMediaProbe.Lease(card);
        assertTrue(lease.hide());
        nativeBackground.setAlpha(207);
        assertTrue(lease.hide());
        assertEquals(null, card.getBackground());
        lease.restore();
        assertEquals(207, nativeBackground.getAlpha());
        assertTrue(card.getBackground() == nativeBackground);
    }

    @Test public void nullBackgroundIsNotProbed() {
        View card = new View(activity);
        LockMediaProbe.Lease lease = new LockMediaProbe.Lease(card);
        assertFalse(lease.valid());
        lease.hide();
        lease.restore();
        assertEquals(null, card.getBackground());
    }

    @Test public void modesIsolateIndependentNativeLayers() {
        assertTrue(LockMediaProbe.hideInner(1));
        assertFalse(LockMediaProbe.hideOuter(1));
        assertFalse(LockMediaProbe.hideInner(2));
        assertTrue(LockMediaProbe.hideOuter(2));
        assertTrue(LockMediaProbe.hideInner(3));
        assertTrue(LockMediaProbe.hideOuter(3));
        assertFalse(LockMediaProbe.hideInner(0));
        assertFalse(LockMediaProbe.hideOuter(0));
        assertTrue(LockMediaProbe.shouldProbe(false, 1));
        assertTrue(LockMediaProbe.shouldProbe(false, 2));
        assertTrue(LockMediaProbe.shouldProbe(false, 3));
        assertFalse(LockMediaProbe.shouldProbe(false, 0));
        assertFalse(LockMediaProbe.shouldProbe(true, 1));
        assertFalse(LockMediaProbe.shouldProbe(true, 2));
        assertFalse(LockMediaProbe.shouldProbe(true, 3));
    }
}

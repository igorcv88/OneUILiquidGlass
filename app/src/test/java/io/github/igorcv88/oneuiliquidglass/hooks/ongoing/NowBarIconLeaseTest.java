package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.widget.ImageView;
import io.github.igorcv88.oneuiliquidglass.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Only an adaptive icon with a real monochrome layer may lose its coloured app disc. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NowBarIconLeaseTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    @Test public void nativeMonochromeGlyphReplacesAdaptiveIconAndRestoresExactly() {
        FakeCardView disc = new FakeCardView(activity);
        ImageView icon = new ImageView(activity);
        disc.addView(icon);
        icon.setImageResource(R.drawable.oulg_launcher);
        Drawable nativeIcon = icon.getDrawable();
        assertTrue(nativeIcon instanceof AdaptiveIconDrawable);
        assertNotNull(((AdaptiveIconDrawable) nativeIcon).getMonochrome());
        int nativeColor = disc.getCardBackgroundColor().getDefaultColor();
        NowBarIconLease lease = new NowBarIconLease();
        lease.sync(icon, true);
        Drawable mono = icon.getDrawable();
        assertNotSame(nativeIcon, mono);
        assertSame("the replacement icon cannot keep the native adaptive drawable alive",
                nativeIcon, io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(lease, "original"));
        assertEquals(0, disc.getCardBackgroundColor().getDefaultColor());
        int writes = disc.colorWrites;
        for (int i = 0; i < 30; i++) lease.sync(icon, true);
        assertSame(mono, icon.getDrawable());
        assertEquals("stable pre-draw must not rewrite CardView", writes, disc.colorWrites);
        // Samsung replaces the disc colour while glass is still showing.
        disc.setCardBackgroundColor(0xff6644aa);
        lease.sync(icon, true);
        assertEquals(0, disc.getCardBackgroundColor().getDefaultColor());
        lease.sync(icon, false);
        assertSame(nativeIcon, icon.getDrawable());
        assertNull("the native icon is released after restoration",
                io.github.igorcv88.oneuiliquidglass.hooks.Reflect.read(lease, "original"));
        assertEquals(0xff6644aa, disc.getCardBackgroundColor().getDefaultColor());
        assertNotEquals(0, nativeColor);
    }

    @Test public void vectorOnlyIconIsNeverReplaced() {
        FakeCardView disc = new FakeCardView(activity);
        ImageView icon = new ImageView(activity);
        disc.addView(icon);
        Drawable vector = new VectorDrawable();
        icon.setImageDrawable(vector);
        int nativeColor = disc.getCardBackgroundColor().getDefaultColor();
        NowBarIconLease lease = new NowBarIconLease();
        lease.sync(icon, true);
        assertSame(vector, icon.getDrawable());
        assertEquals(nativeColor, disc.getCardBackgroundColor().getDefaultColor());
    }

    @Test public void tintedAdaptiveGlyphFollowsContrastAndRestoresNativeTint() {
        FakeCardView disc = new FakeCardView(activity);
        ImageView icon = new ImageView(activity);
        disc.addView(icon);
        icon.setImageResource(R.drawable.oulg_launcher);
        Drawable original = icon.getDrawable();
        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(0xff5566cc);
        icon.setImageTintList(tint);
        NowBarIconLease lease = new NowBarIconLease();
        lease.sync(icon, true);
        assertNotSame(original, icon.getDrawable());
        assertNull(icon.getImageTintList());
        lease.applyTone(true);
        assertEquals(android.graphics.Color.BLACK,
                ((android.graphics.PorterDuffColorFilter) icon.getDrawable().getColorFilter()).getColor());
        lease.sync(icon, true);
        lease.applyTone(false);
        assertEquals(android.graphics.Color.WHITE,
                ((android.graphics.PorterDuffColorFilter) icon.getDrawable().getColorFilter()).getColor());
        lease.restore();
        assertSame(original, icon.getDrawable());
        assertSame(tint, icon.getImageTintList());
    }
}


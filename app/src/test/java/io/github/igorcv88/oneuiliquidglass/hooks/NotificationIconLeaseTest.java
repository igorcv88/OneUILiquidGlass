package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NotificationIconLeaseTest {
    @Test public void expansionCannotKeepNativeWhiteWhenPaletteStillRequestsBlack() {
        ImageView image = new ImageView(Robolectric.buildActivity(Activity.class).setup().get());
        ColorDrawable nativeIcon = new ColorDrawable(Color.WHITE);
        image.setImageDrawable(nativeIcon);
        PorterDuffColorFilter nativeFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        image.setColorFilter(nativeFilter);
        ColorDrawable glyph = new ColorDrawable(Color.WHITE);
        NotificationIconTreatment.Lease lease = new NotificationIconTreatment.Lease(image, glyph, Color.BLACK);
        assertSame(image.getColorFilter(), glyph.getColorFilter());
        assertNotSame(nativeFilter, glyph.getColorFilter());
        android.graphics.ColorFilter applied = glyph.getColorFilter();
        // Samsung expands a template and recolours the existing icon without changing its identity.
        PorterDuffColorFilter expandedFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        image.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        image.setColorFilter(expandedFilter);
        lease.tone(Color.BLACK);
        assertSame(applied, image.getColorFilter());
        assertSame(applied, glyph.getColorFilter());
        // A second path writes directly to the drawable while ImageView retains its cached filter.
        glyph.setColorFilter(nativeFilter);
        lease.tone(Color.BLACK);
        assertSame(applied, glyph.getColorFilter());
        lease.restore();
        assertSame(nativeIcon, image.getDrawable());
        assertSame(expandedFilter, image.getColorFilter());
        assertEquals(Color.WHITE, image.getImageTintList().getDefaultColor());
        assertFalse(NotificationIconTreatment.managed(image));
        assertFalse(ShelfIconTint.owns(image));
    }
}

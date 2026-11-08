package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.ColorFilter;
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
public class ShelfIconTintTest {
    private ImageView icon() {
        ImageView view = new ImageView(Robolectric.buildActivity(Activity.class).setup().get());
        view.setImageDrawable(new ColorDrawable(Color.WHITE));
        return view;
    }

    @Test public void repairsDrawableEvenWhenImageViewStillCachesOurFilter() {
        ImageView view = icon();
        ShelfIconTint lease = new ShelfIconTint();
        assertTrue(lease.apply(view, Color.BLACK));
        ColorFilter applied = view.getColorFilter();
        assertTrue(lease.intact(view));
        assertFalse("a new palette is not an OEM overwrite", lease.current(view, Color.WHITE));
        assertTrue(lease.intact(view));
        view.getDrawable().setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        assertSame(applied, view.getColorFilter());
        assertFalse(lease.current(view, Color.BLACK));
        assertTrue(lease.apply(view, Color.BLACK));
        assertSame(applied, view.getDrawable().getColorFilter());
        assertFalse("stable palette does not write", lease.apply(view, Color.BLACK));
        lease.restore(view);
    }

    @Test public void restoresBothNativeFiltersAndReleasesOwnership() {
        ImageView view = icon();
        ColorFilter original = new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
        view.setColorFilter(original);
        ShelfIconTint lease = new ShelfIconTint();
        lease.apply(view, Color.BLACK);
        assertTrue(ShelfIconTint.owns(view));
        lease.apply(view, Color.WHITE);
        lease.restore(view);
        assertSame(original, view.getColorFilter());
        assertSame(original, view.getDrawable().getColorFilter());
        assertFalse(ShelfIconTint.owns(view));
    }

    @Test public void rebindDoesNotLeaveOurFilterOnDetachedDrawable() {
        ImageView view = icon();
        android.graphics.drawable.Drawable old = view.getDrawable();
        ShelfIconTint lease = new ShelfIconTint();
        lease.apply(view, Color.BLACK);
        view.setImageDrawable(new ColorDrawable(Color.WHITE));
        lease.apply(view, Color.BLACK);
        assertNull(old.getColorFilter());
        lease.restore(view);
        assertNull(view.getColorFilter());
        assertNull(view.getDrawable().getColorFilter());
    }

    @Test public void releasePreservesNewerDirectOemDrawableFilter() {
        ImageView view = icon();
        ShelfIconTint lease = new ShelfIconTint();
        lease.apply(view, Color.BLACK);
        ColorFilter newer = new PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.SRC_IN);
        view.getDrawable().setColorFilter(newer);
        lease.restore(view);
        assertNull(view.getColorFilter());
        assertSame(newer, view.getDrawable().getColorFilter());
    }
}

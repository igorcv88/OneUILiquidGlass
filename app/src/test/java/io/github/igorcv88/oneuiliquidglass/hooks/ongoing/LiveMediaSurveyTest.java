package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The media map only reads: no view or drawable changes, no text content. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveMediaSurveyTest {
    static final class FakeMediaCardView extends FrameLayout {
        FakeMediaCardView(Activity a) { super(a); }
    }

    @Test public void mediaRootsAreMatchedByClassName() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(LiveMediaSurvey.media(new FakeMediaCardView(a)));
        assertFalse(LiveMediaSurvey.media(new FrameLayout(a)));
    }

    @Test public void nodeDescriptionIsReadOnly() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        ImageView art = new ImageView(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff112233);
        ColorDrawable image = new ColorDrawable(0xff445566);
        art.setBackground(bg);
        art.setImageDrawable(image);
        art.setAlpha(0.8f);
        art.layout(0, 0, 300, 300);

        String line = LiveMediaSurvey.node(art);
        assertTrue(line, line.contains("class=android.widget.ImageView"));
        assertTrue(line, line.contains("wh=300x300"));
        assertTrue(line, line.contains("image=ColorDrawable@"));
        assertSame(bg, art.getBackground());
        assertSame(image, art.getDrawable());
        assertEquals(0.8f, art.getAlpha(), 0f);
        assertEquals(255, bg.getAlpha());
        assertNull(art.getColorFilter());
        assertEquals(View.VISIBLE, art.getVisibility());
    }
}

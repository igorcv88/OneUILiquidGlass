package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureBackdropVisibilityTest {
    private static final class TrackedView extends View {
        int invalidations;
        TrackedView(Activity activity) { super(activity); }
        @Override public void invalidate() { invalidations++; super.invalidate(); }
    }

    @Test public void hiddenLaneKeepsReadyFrameWithoutInvalidatingAndDisabledLaneDoesNotReceive() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TrackedView view = new TrackedView(activity);
        activity.setContentView(view);
        AtomicInteger hz = new AtomicInteger(60);
        CaptureBackdrop backdrop = new CaptureBackdrop(view, false, hz::get);
        CaptureHub.Frame frame = new CaptureHub.Frame(
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), new Rect(0, 0, 8, 8), .5f, false);
        try {
            assertTrue(view.isAttachedToWindow());
            view.setVisibility(View.INVISIBLE);
            view.invalidations = 0;
            backdrop.onFrame(frame);
            assertSame("hidden lane is prepared for the next opening", frame, backdrop.frame());
            assertEquals(0, view.invalidations);
            view.setVisibility(View.VISIBLE);
            view.invalidations = 0;
            hz.set(0);
            CaptureHub.Frame ignored = new CaptureHub.Frame(frame.bitmap, frame.crop, .5f, false);
            backdrop.onFrame(ignored);
            assertSame(frame, backdrop.frame());
            assertEquals(0, view.invalidations);
            hz.set(60);
            backdrop.onFrame(ignored);
            assertSame(ignored, backdrop.frame());
            assertEquals(1, view.invalidations);
            backdrop.release();
            backdrop.onFrame(frame);
            assertNull(backdrop.frame());
        } finally { backdrop.release(); frame.bitmap.recycle(); }
    }
}

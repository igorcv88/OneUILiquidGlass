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
    @Test public void broadcastDoesNotDeliverVisualFramesToHiddenOrDisabledLane() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        activity.setContentView(view);
        AtomicInteger hz = new AtomicInteger(60);
        CaptureBackdrop backdrop = new CaptureBackdrop(view, false, hz::get);
        CaptureHub.Frame frame = new CaptureHub.Frame(
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), new Rect(0, 0, 8, 8), .5f, false);
        try {
            assertTrue(view.isAttachedToWindow());
            view.setVisibility(View.INVISIBLE);
            backdrop.onFrame(frame);
            assertNull(backdrop.frame());
            view.setVisibility(View.VISIBLE);
            hz.set(0);
            backdrop.onFrame(frame);
            assertNull(backdrop.frame());
            hz.set(60);
            backdrop.onFrame(frame);
            assertSame(frame, backdrop.frame());
            backdrop.release();
            backdrop.onFrame(frame);
            assertNull(backdrop.frame());
        } finally { backdrop.release(); frame.bitmap.recycle(); }
    }
}

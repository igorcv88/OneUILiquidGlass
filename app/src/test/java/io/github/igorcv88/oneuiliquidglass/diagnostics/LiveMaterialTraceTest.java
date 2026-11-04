package io.github.igorcv88.oneuiliquidglass.diagnostics;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.widget.FrameLayout;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveMaterialTraceTest {
    @Test public void confinesTelemetryToTheExactStackInsteadOfAnotherWindow() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout stackA = new FrameLayout(a);
        FrameLayout stackB = new FrameLayout(a);
        FrameLayout cardA = new FrameLayout(a);
        FrameLayout materialHost = new FrameLayout(a);
        FrameLayout otherCard = new FrameLayout(a);
        stackA.addView(cardA);
        cardA.addView(materialHost);
        stackB.addView(otherCard);

        assertTrue(LiveMaterialTrace.belongsTo(materialHost, stackA));
        assertFalse(LiveMaterialTrace.belongsTo(materialHost, stackB));
        assertFalse(LiveMaterialTrace.belongsTo(otherCard, stackA));
        assertFalse(LiveMaterialTrace.belongsTo(null, stackA));
        assertFalse(LiveMaterialTrace.belongsTo(materialHost, null));
    }

    @Test public void inactiveAndExpiredTraceNeverChangesMaterialOrNativeBackground() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout stack = new FrameLayout(a);
        FrameLayout host = new FrameLayout(a);
        stack.addView(host);
        ColorDrawable nativeFill = new ColorDrawable(0xff225577);
        LiveGlassDrawable glass = new LiveGlassDrawable(nativeFill, new float[8],
                0xff225577, new LiveGlassDrawable.Spec(0.2f, 64, 0xffffffff,
                0xffcccccc, 2f, 0x22ffffff), 1f);
        host.setBackground(glass);
        // Expired burst, to guarantee no observer can change the native material.
        LiveMaterialTrace.begin(stack, 20, SystemClock.uptimeMillis() - 1000);
        LiveMaterialTrace.note(host, glass, "BLUR_ASSIGNED", null);
        LiveMaterialTrace.note(host, glass, "DRAW_NATIVE", null);
        assertSame(glass, host.getBackground());
        assertSame(nativeFill, glass.nativeDrawable());
        assertEquals(255, nativeFill.getAlpha());
        assertEquals(0xff225577, nativeFill.getColor());
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.internal.widget.NotificationRowIconView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NowBarIconVisibilityBridgeTest {
    @Test public void hiddenMeasuredNotificationIconGetsTemporaryOverlayWithoutAlphaWrites() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout expanded = new FrameLayout(activity);
        FrameLayout header = new FrameLayout(activity);
        NotificationRowIconView icon = new NotificationRowIconView(activity);
        icon.setImageDrawable(new ColorDrawable(Color.WHITE));
        header.addView(icon, new FrameLayout.LayoutParams(100, 100));
        expanded.addView(header, new FrameLayout.LayoutParams(420, 160));
        activity.setContentView(expanded);
        expanded.layout(0, 0, 600, 400);
        header.layout(0, 0, 420, 160);
        icon.layout(10, 15, 110, 115);
        icon.setAlpha(0f);
        assertTrue(NowBarIconVisibilityBridge.eligible(icon, expanded, true, null));
        NowBarIconVisibilityBridge bridge = new NowBarIconVisibilityBridge();
        bridge.sync(icon, expanded, true, null);
        assertTrue(bridge.active());
        assertEquals("Samsung's alpha is never overwritten", 0f, icon.getAlpha(), 0f);
        icon.setAlpha(1f);
        bridge.onPreDraw();
        assertFalse(bridge.active());
    }

    @Test public void bridgeNeverTouchesClockOrNormalIconViews() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        NotificationRowIconView target = new NotificationRowIconView(activity);
        root.addView(target);
        activity.setContentView(root);
        root.layout(0, 0, 600, 400);
        target.layout(0, 0, 100, 100);
        assertFalse(NowBarIconVisibilityBridge.eligible(target, root, true,
                "com.sec.android.app.clockpackage"));
        assertFalse(NowBarIconVisibilityBridge.eligible(new ImageView(activity), root, true, null));
        assertFalse(NowBarIconVisibilityBridge.eligible(target, root, false, null));
    }
}

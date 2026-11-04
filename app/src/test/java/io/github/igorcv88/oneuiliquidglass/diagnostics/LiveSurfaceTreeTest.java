package io.github.igorcv88.oneuiliquidglass.diagnostics;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.annotation.Config;

/** Opt-in live snapshots describe drawable ownership without recording notification contents. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveSurfaceTreeTest {
    @Test public void snapshotIncludesDrawableAndIconButNeverText() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(new ColorDrawable(Color.BLUE));
        TextView label = new TextView(activity);
        label.setText("PRIVATE_NOTIFICATION_CONTENT");
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ColorDrawable(Color.RED));
        root.addView(label);
        root.addView(icon);
        activity.setContentView(root);
        root.layout(0, 0, 400, 200);

        int start = ShadowLog.getLogs().size();
        try {
            Probe.trace = true;
            Probe.liveSurfaceTree(root, "CARD");
        } finally {
            Probe.trace = false;
        }
        StringBuilder output = new StringBuilder();
        List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
        for (int i = start; i < logs.size(); i++)
            if ("OULG".equals(logs.get(i).tag)) output.append(logs.get(i).msg).append('\n');
        assertTrue(output.toString(), output.toString().contains("event=LIVE_DETAIL_BEGIN surface=CARD"));
        assertTrue(output.toString(), output.toString().contains("ColorDrawable"));
        assertTrue(output.toString(), output.toString().contains("imageTint="));
        assertTrue(output.toString(), output.toString().contains("event=LIVE_DETAIL_END surface=CARD"));
        assertFalse(output.toString(), output.toString().contains("PRIVATE_NOTIFICATION_CONTENT"));
    }

    @Test public void tracingDisabledEmitsNoLiveDetail() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        int start = ShadowLog.getLogs().size();
        Probe.trace = false;
        Probe.liveSurfaceTree(root, "CHIP");
        Probe.liveSurfaceLater(root, "CHIP", 10);
        List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
        for (int i = start; i < logs.size(); i++)
            assertFalse(logs.get(i).msg.contains("LIVE_DETAIL"));
    }
}

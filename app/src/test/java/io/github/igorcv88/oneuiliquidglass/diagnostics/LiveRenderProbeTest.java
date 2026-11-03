package io.github.igorcv88.oneuiliquidglass.diagnostics;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveRenderProbeTest {
    @Test public void logsOnlyChangesAndObservesReturningToAnEarlierMode() {
        LiveRenderProbe p = new LiveRenderProbe();
        assertTrue(p.changed("OWN_REGION"));
        for (int i = 0; i < 120; i++) assertFalse("no per-frame log", p.changed("OWN_REGION"));
        assertTrue(p.changed("NATIVE"));
        assertTrue(p.changed("OWN_REGION"));
    }

    @Test public void twoPartStateAvoidsAllocatingAndCatchesReasonChanges() {
        LiveRenderProbe p = new LiveRenderProbe();
        assertTrue(p.changed("REGIONLESS", "samsungAncestor"));
        for (int i = 0; i < 120; i++) assertFalse(p.changed("REGIONLESS", "samsungAncestor"));
        assertTrue(p.changed("NATIVE", "unavailable"));
        assertTrue(p.changed("REGIONLESS", "samsungAncestor"));
        assertTrue(p.changed("REGIONLESS", "regionRecovered"));
    }

    @Test public void snapshotsContainStructureWithoutTextOrPackageData() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(new ColorDrawable(0xff112233));
        View child = new View(activity);
        root.addView(child);
        activity.setContentView(root);
        String tree = LiveRenderProbe.ancestors(child);
        assertTrue(tree, tree.contains("ColorDrawable"));
        assertTrue(tree, tree.contains("ff112233"));
        assertFalse(tree, tree.contains(activity.getPackageName()));
        String host = LiveRenderProbe.view(child);
        assertTrue(host, host.contains("hwView="));
        assertTrue(host, host.contains("windowType="));
    }

    @Test public void snapshotsOfMissingHostAreSafe() {
        assertEquals("view=null", LiveRenderProbe.view(null));
        assertEquals("-", LiveRenderProbe.ancestors(null));
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.Color;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;
import android.view.ViewGroup;
import org.robolectric.Robolectric;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The overflow dot needs its own slot after the user-configured real icon limit. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NotificationCompactIconHooksTest {
    @Test public void missingShelfCountUsesSharedShadeGridWithoutWalkingOtherRows() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        root.addView(icon, new FrameLayout.LayoutParams(132, 132));
        activity.setContentView(root);
        icon.layout(0, 0, 132, 132);
        Constructor<ToneGrid> ctor = ToneGrid.class.getDeclaredConstructor(
                int.class, int.class, float[].class, float[].class, int.class, long.class);
        ctor.setAccessible(true);
        ToneGrid grid = ctor.newInstance(0, 200, new float[]{.88f, .88f},
                new float[]{.93f, .93f}, 1, 0L);
        NotificationCompactIconHooks hook = new NotificationCompactIconHooks(
                getClass().getClassLoader(), () -> true, () -> false,
                () -> grid, () -> Boolean.TRUE);
        assertNotNull(hook.shelfSceneInk(icon));
        // Repeated pre-draws reuse the same decision (no new capture or tree scan).
        assertEquals(hook.shelfSceneInk(icon), hook.shelfSceneInk(icon));
    }

    @Test public void shelfCountCanLiveInWindowSiblingOutsideCompactIconContainer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout windowContent = new FrameLayout(activity);
        FrameLayout iconContainer = new FrameLayout(activity);
        ImageView icon = new ImageView(activity);
        iconContainer.addView(icon);
        windowContent.addView(iconContainer);
        TextView count = new TextView(activity);
        count.setText("3 more notifications");
        count.setTextColor(Color.BLACK);
        windowContent.addView(count);
        activity.setContentView(windowContent);
        NotificationCompactIconHooks hook = new NotificationCompactIconHooks(
                getClass().getClassLoader(), () -> true, () -> false);
        // The shelf counter does not belong to the icon container subtree.
        assertSame(count, hook.findShelfCountNear(icon));
    }

    @Test public void shelfCanAdoptItsCounterOutsideTheIconContainer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout shelf = new FrameLayout(activity);
        TextView count = new TextView(activity);
        count.setText("3 more notifications");
        count.setTextColor(Color.BLACK);
        shelf.addView(count);
        activity.setContentView(shelf);
        NotificationCompactIconHooks hook = new NotificationCompactIconHooks(
                getClass().getClassLoader(), () -> true, () -> false);

        hook.bindShelfLabel(shelf);
        @SuppressWarnings("unchecked")
        WeakReference<TextView> binding =
                (WeakReference<TextView>) Reflect.read(hook, "sharedShelfLabel");
        assertSame(count, binding.get());
        assertEquals(Color.BLACK, binding.get().getCurrentTextColor());
    }

    @Test public void fourthIconReservesDotSpaceWithoutMiscountingTheLimit() {
        assertEquals(270, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 4));
        assertEquals(270, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 3));
    }

    @Test public void innerStatusBranchReservesAndRestoresItsLayoutBudgetWithoutRepeatedWrites() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout panel = new FrameLayout(activity);
        ViewGroup.LayoutParams nativeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 64);
        panel.setLayoutParams(nativeParams);
        NotificationCompactIconHooks hook = new NotificationCompactIconHooks(
                getClass().getClassLoader(), () -> true, () -> false);
        hook.setStatusMeasuredWidth(panel, 270);
        assertEquals(270, panel.getLayoutParams().width);
        ViewGroup.LayoutParams reservedParams = panel.getLayoutParams();
        hook.setStatusMeasuredWidth(panel, 270);
        assertEquals("stable sizing keeps existing layout params", reservedParams,
                panel.getLayoutParams());
        hook.setStatusMeasuredWidth(panel, 0);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, panel.getLayoutParams().width);
    }

    @Test public void nativeOrUnmeasuredStatusBarDoesNotForceAnyWidth() {
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(0, 54, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 0, 5));
        assertEquals(0, NotificationCompactIconHooks.statusOverflowBudget(4, 54, 0));
    }
}

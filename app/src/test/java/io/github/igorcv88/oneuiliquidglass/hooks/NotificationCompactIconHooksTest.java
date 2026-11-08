package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Color;
import java.lang.ref.WeakReference;
import android.view.ViewGroup;
import org.robolectric.Robolectric;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The overflow dot needs its own slot after the user-configured real icon limit. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NotificationCompactIconHooksTest {
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

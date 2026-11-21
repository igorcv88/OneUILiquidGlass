package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.BlendMode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class RemoteViewsToneTest {
    private static final int LIGHT = 0xffefefef;
    private static final int DARK = 0xff101010;
    private final Context context = RuntimeEnvironment.getApplication();
    private final ExpandableNotificationRow row = new ExpandableNotificationRow(context);

    private static final class ExpandableNotificationRow extends FrameLayout {
        ExpandableNotificationRow(Context context) { super(context); }
    }

    private ProgressBar bar(FrameLayout parent, int id, int color) {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setId(id);
        bar.setProgressDrawable(new ColorDrawable(LIGHT));
        bar.setProgressTintBlendMode(BlendMode.SRC_IN);
        bar.setProgressTintList(ColorStateList.valueOf(color));
        bar.setMax(100);
        bar.setProgress(42);
        parent.addView(bar);
        return bar;
    }

    private ImageButton action() {
        ImageButton button = new ImageButton(context);
        button.setBackground(null);
        button.setOnClickListener(view -> {});
        button.setImageDrawable(new VectorDrawable());
        button.setImageTintBlendMode(BlendMode.SRC_IN);
        button.setImageTintList(ColorStateList.valueOf(LIGHT));
        row.addView(button);
        return button;
    }

    private static boolean apply(View view, boolean swap, boolean lightText) {
        return RemoteViewsTone.apply(view, swap, lightText, false, null);
    }

    @Test public void progressUsesItsTypeWithOrWithoutAnIdAndRestores() {
        for (int id : new int[] { View.NO_ID, 0x7f080123, 0x80080123 }) {
            ProgressBar control = bar(row, id, LIGHT);
            ColorStateList tint = control.getProgressTintList();
            assertTrue(apply(control, true, true));
            assertEquals(DARK, control.getProgressTintList().getDefaultColor());
            assertEquals(42, control.getProgress());
            assertEquals(100, control.getMax());
            assertFalse(apply(control, true, true));
            assertFalse(RemoteViewsTone.drift(control));
            assertTrue(apply(control, false, true));
            assertSame(tint, control.getProgressTintList());
        }
    }

    @Test public void darkPublisherToneAlsoAdapts() {
        ProgressBar control = bar(row, View.NO_ID, DARK);
        assertTrue(apply(control, true, false));
        assertEquals(LIGHT, control.getProgressTintList().getDefaultColor());
        assertTrue(apply(control, false, false));
    }

    @Test public void progressBehindPrivateMaterialOrOutsideRowIsNative() {
        FrameLayout privateCard = new FrameLayout(context);
        privateCard.setBackgroundColor(0xff222222);
        row.addView(privateCard);
        ProgressBar control = bar(privateCard, View.NO_ID, LIGHT);
        assertFalse(apply(control, true, true));
        ProgressBar loose = bar(new FrameLayout(context), View.NO_ID, LIGHT);
        assertFalse(apply(loose, true, true));
        privateCard.setBackgroundColor(0x00000000);
        assertTrue(apply(control, true, true));
        privateCard.setBackgroundColor(0xff222222);
        assertTrue(apply(control, true, true));
        assertEquals(LIGHT, control.getProgressTintList().getDefaultColor());
    }

    @Test public void ambiguousGraphicsAndIntentionalTintsStayNative() {
        ProgressBar control = bar(row, View.NO_ID, 0xff359af9);
        assertFalse(apply(control, true, true));
        control.setProgressTintList(ColorStateList.valueOf(0xff727272));
        assertFalse(apply(control, true, false));
        ColorStateList states = new ColorStateList(
                new int[][] { {android.R.attr.state_pressed}, {} }, new int[] { LIGHT, LIGHT });
        assertNull(RemoteViewsTone.target(states, true));
        control.setProgressTintList(ColorStateList.valueOf(LIGHT));
        control.setProgressDrawable(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { 0xffffffff, 0xff000000 }));
        assertFalse(apply(control, true, true));
        FrameLayout shape = new FrameLayout(context);
        shape.setBackgroundColor(LIGHT);
        row.addView(shape);
        assertFalse(apply(shape, true, true));
        assertNull(shape.getBackgroundTintList());
    }

    @Test public void explicitVectorActionHasToneButOrdinaryImageDoesNot() {
        ImageButton button = action();
        ColorStateList nativeTint = button.getImageTintList();
        ImageView image = new ImageView(context);
        image.setImageDrawable(new VectorDrawable());
        image.setImageTintBlendMode(BlendMode.SRC_IN);
        image.setImageTintList(nativeTint);
        row.addView(image);
        assertTrue(apply(button, true, true));
        assertEquals(DARK, button.getImageTintList().getDefaultColor());
        assertFalse(apply(image, true, true));
        assertSame(nativeTint, image.getImageTintList());
        assertTrue(apply(button, false, true));
        assertSame(nativeTint, button.getImageTintList());
    }

    @Test public void clickBoundBitmapAndStatefulVectorActionArePreserved() {
        ImageButton button = action();
        ColorStateList stateful = new ColorStateList(
                new int[][] { {android.R.attr.state_pressed}, {} }, new int[] { LIGHT, DARK });
        button.setImageTintList(stateful);
        assertFalse(apply(button, true, true));
        assertSame(stateful, button.getImageTintList());
        button.setImageTintList(ColorStateList.valueOf(LIGHT));
        button.setImageBitmap(android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888));
        assertFalse(apply(button, true, true));
        assertEquals(LIGHT, button.getImageTintList().getDefaultColor());
    }

    @Test public void replacementRestoresViewTintButKeepsNewDrawable() {
        ImageButton button = action();
        ColorStateList nativeTint = button.getImageTintList();
        assertTrue(apply(button, true, true));
        ColorDrawable replacement = new ColorDrawable(0xffaa2222);
        button.setImageDrawable(replacement);
        assertTrue(RemoteViewsTone.drift(button));
        assertTrue(apply(button, true, true));
        assertSame(replacement, button.getDrawable());
        assertSame(nativeTint, button.getImageTintList());
        assertFalse(RemoteViewsTone.owns(button));
    }

    @Test public void explicitPublisherRewriteWinsEvenWithSameColor() {
        ProgressBar control = bar(row, View.NO_ID, LIGHT);
        assertTrue(apply(control, true, true));
        ColorStateList publisher = ColorStateList.valueOf(DARK);
        control.setProgressTintList(publisher);
        assertTrue(RemoteViewsTone.drift(control));
        assertFalse(apply(control, false, true));
        assertSame(publisher, control.getProgressTintList());
    }

    @Test public void animationUsesCardTimelineAndRestoresOriginalList() {
        ProgressBar control = bar(row, View.NO_ID, LIGHT);
        ColorStateList original = control.getProgressTintList();
        InkAnimator.Timeline timeline = new InkAnimator.Timeline(100);
        assertTrue(RemoteViewsTone.apply(control, true, true, true, timeline));
        assertSame(original, control.getProgressTintList());
        InkAnimator.frame(timeline.start + 50);
        int midway = control.getProgressTintList().getDefaultColor();
        assertNotEquals(LIGHT, midway);
        assertNotEquals(DARK, midway);
        assertFalse(RemoteViewsTone.drift(control));
        InkAnimator.frame(timeline.start + 100);
        assertEquals(DARK, control.getProgressTintList().getDefaultColor());
        InkAnimator.Timeline restore = new InkAnimator.Timeline(100);
        assertTrue(RemoteViewsTone.apply(control, false, true, true, restore));
        InkAnimator.frame(restore.start + 100);
        assertSame(original, control.getProgressTintList());
        assertFalse(RemoteViewsTone.owns(control));
    }

    @Test public void detachedControlReleasesItsLeaseBeforeItMovesToAnotherOwner() {
        ProgressBar control = bar(row, View.NO_ID, LIGHT);
        org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get().setContentView(row);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(control.isAttachedToWindow());
        ColorStateList original = control.getProgressTintList();
        assertTrue(apply(control, true, true));
        row.removeView(control);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertSame(original, control.getProgressTintList());
        assertFalse(RemoteViewsTone.owns(control));
    }

    @Test public void sameHostReattachKeepsTintThroughDetachAndQueuedCleanup() {
        ProgressBar control = bar(row, View.NO_ID, LIGHT);
        org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get().setContentView(row);
        var looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());
        looper.idle();
        assertTrue(apply(control, true, true));
        ColorStateList applied = control.getProgressTintList();
        row.removeView(control);
        assertSame(applied, control.getProgressTintList());
        row.addView(control);
        looper.idle();
        assertTrue(RemoteViewsTone.owns(control));
        assertSame(applied, control.getProgressTintList());
        assertFalse(apply(control, true, true));

        row.removeView(control);
        ColorStateList rebound = ColorStateList.valueOf(DARK);
        control.setProgressTintList(rebound);
        row.addView(control);
        looper.idle();
        assertFalse(RemoteViewsTone.owns(control));
        assertSame(rebound, control.getProgressTintList());
    }

    @Test public void reattachInSameWindowToDifferentHostRestoresOldLease() {
        ProgressBar control = bar(row, View.NO_ID, LIGHT);
        FrameLayout window = new FrameLayout(context);
        ExpandableNotificationRow other = new ExpandableNotificationRow(context);
        window.addView(row);
        window.addView(other);
        org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get().setContentView(window);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        ColorStateList original = control.getProgressTintList();
        assertTrue(apply(control, true, true));
        row.removeView(control);
        other.addView(control);
        assertSame(original, control.getProgressTintList());
        assertFalse(RemoteViewsTone.owns(control));
    }

    @Test public void platformActionTintIdentityDistinguishesEqualPublisherRewrite() {
        ImageButton button = action();
        assertTrue(apply(button, true, true));
        ColorStateList applied = button.getImageTintList();
        assertSame(applied, button.getImageTintList());
        assertFalse(RemoteViewsTone.drift(button));
        ColorStateList publisher = ColorStateList.valueOf(DARK);
        assertNotSame(applied, publisher);
        button.setImageTintList(publisher);
        assertTrue(RemoteViewsTone.drift(button));
        assertFalse(apply(button, false, true));
        assertSame(publisher, button.getImageTintList());
    }

    @Test public void diagnosticsIdentifyPaintedNodeWithoutReadingContentDescription() {
        FrameLayout painted = new FrameLayout(context);
        painted.setId(android.R.id.background);
        painted.setBackgroundColor(LIGHT);
        painted.setContentDescription("private-content");
        row.addView(painted);
        String line = RemoteViewsTone.diagnostic(painted, -1, true, "painted-container-role-unverified");
        assertTrue(line.contains("FrameLayout#background@"));
        assertTrue(line.contains("drawable=ColorDrawable"));
        assertTrue(line.contains("material=light"));
        assertTrue(line.contains("glassPath=false"));
        assertTrue(line.contains("ancestors=ExpandableNotificationRow"));
        assertFalse(line.contains("private-content"));
    }

    @Test public void foregroundWalkRoutesTextAndBothLayoutBranches() {
        FrameLayout contracted = new FrameLayout(context);
        FrameLayout expanded = new FrameLayout(context);
        row.addView(contracted);
        row.addView(expanded);
        TextView label = new TextView(context);
        label.setId(0x7f080124);
        label.setTextColor(LIGHT);
        contracted.addView(label);
        ProgressBar first = bar(contracted, 0x7f080123, LIGHT);
        ProgressBar second = bar(expanded, 0x7f080123, LIGHT);
        ArrayList<View> tracked = new ArrayList<>();
        Foreground.apply(row, true, true, tracked);
        assertEquals(DARK, label.getCurrentTextColor());
        assertTrue(tracked.contains(first));
        assertTrue(tracked.contains(second));
        Foreground.apply(row, false, true, tracked);
        assertEquals(LIGHT, label.getCurrentTextColor());
        assertEquals(LIGHT, first.getProgressTintList().getDefaultColor());
        assertEquals(LIGHT, second.getProgressTintList().getDefaultColor());
    }
}

package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class NotificationProgressToneTest {
    private final Context ctx = RuntimeEnvironment.getApplication();

    /** Stands in for SystemUI's row; only the class-name suffix is checked. */
    private static final class ExpandableNotificationRow extends FrameLayout {
        ExpandableNotificationRow(Context c) { super(c); }
    }

    private ExpandableNotificationRow row;
    /** row > main_column > android:id/progress, as in the 1DM accessibility dump. */
    private ProgressBar templateBar() {
        row = new ExpandableNotificationRow(ctx);
        LinearLayout column = new LinearLayout(ctx);
        row.addView(column);
        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setId(android.R.id.progress);
        bar.setMax(100);
        bar.setProgress(42);
        bar.setSecondaryProgress(60);
        column.addView(bar);
        return bar;
    }
    private static ColorStateList c(int color) { return ColorStateList.valueOf(color); }
    private static int tint(ProgressBar b, int channel) { return NotificationProgressTone.get(b, channel).getDefaultColor(); }

    @Test public void targetMirrorsThemeToneOnlyAndKeepsAlpha() {
        assertEquals(Integer.valueOf(0xff000000), NotificationProgressTone.target(0xffffffff, true));
        assertEquals(Integer.valueOf(0x4d000000), NotificationProgressTone.target(0x4dffffff, true));
        assertEquals(Integer.valueOf(0xffe6e6e6), NotificationProgressTone.target(0xff191919, false));
        assertNull(NotificationProgressTone.target(0xff3d8bff, true));   // accent
        assertNull(NotificationProgressTone.target(0xff202020, true));   // dark grey with light text
        assertNull(NotificationProgressTone.target(0x00ffffff, true));   // transparent
        assertTrue(NotificationProgressTone.templateId("android", "progress"));
        assertFalse(NotificationProgressTone.templateId("com.idm.fulldownloader", "progress"));
        assertFalse(NotificationProgressTone.templateId("android", "progress_bar"));
    }

    @Test public void swapsThemeToneChannelsAndRestoresOriginalObjects() {
        ProgressBar b = templateBar();
        ColorStateList progress = c(0xffffffff), track = c(0x4dffffff), secondary = c(0x99f0f0f0), ind = c(0xfffafafa);
        b.setProgressTintList(progress);
        b.setProgressBackgroundTintList(track);
        b.setSecondaryProgressTintList(secondary);
        b.setIndeterminateTintList(ind);

        assertTrue(NotificationProgressTone.apply(b, true, true));
        assertEquals(0xff000000, tint(b, NotificationProgressTone.PROGRESS));
        assertEquals(0x4d000000, tint(b, NotificationProgressTone.BACKGROUND));
        assertEquals(0x990f0f0f, tint(b, NotificationProgressTone.SECONDARY));
        assertEquals(0xff050505, tint(b, NotificationProgressTone.INDETERMINATE));
        assertEquals(42, b.getProgress());
        assertEquals(60, b.getSecondaryProgress());
        assertEquals(100, b.getMax());
        assertTrue(NotificationProgressTone.owns(b));
        // Idempotent while nothing changed.
        assertFalse(NotificationProgressTone.apply(b, true, true));
        assertFalse(NotificationProgressTone.drift(b));

        assertTrue(NotificationProgressTone.apply(b, false, true));
        assertSame(progress, b.getProgressTintList());
        assertSame(track, b.getProgressBackgroundTintList());
        assertSame(secondary, b.getSecondaryProgressTintList());
        assertSame(ind, b.getIndeterminateTintList());
        assertFalse(NotificationProgressTone.owns(b));
    }

    @Test public void accentAndMissingTintsAreLeftAlone() {
        ProgressBar b = templateBar();
        ColorStateList accent = c(0xff3d8bff);
        b.setProgressTintList(accent);
        b.setProgressBackgroundTintList(c(0x4dffffff));
        assertTrue(NotificationProgressTone.apply(b, true, true));
        assertSame(accent, b.getProgressTintList());
        assertEquals(0x4d000000, tint(b, NotificationProgressTone.BACKGROUND));
        assertNull(b.getSecondaryProgressTintList());
        assertNull(b.getIndeterminateTintList());

        ProgressBar onlyAccent = templateBar();
        onlyAccent.setProgressTintList(accent);
        assertFalse(NotificationProgressTone.apply(onlyAccent, true, true));
        assertFalse(NotificationProgressTone.owns(onlyAccent));
        NotificationProgressTone.apply(b, false, true);
    }

    @Test public void publisherRebindBecomesTheNewOriginal() {
        ProgressBar b = templateBar();
        b.setProgressTintList(c(0xffffffff));
        NotificationProgressTone.apply(b, true, true);

        ColorStateList rebound = c(0xffeeeeee);
        b.setProgressTintList(rebound);
        assertTrue(NotificationProgressTone.drift(b));
        assertTrue(NotificationProgressTone.apply(b, true, true));
        assertEquals(0xff111111, tint(b, NotificationProgressTone.PROGRESS));
        NotificationProgressTone.apply(b, false, true);
        assertSame(rebound, b.getProgressTintList());
    }

    @Test public void publisherAccentRebindIsNeverOverwritten() {
        ProgressBar b = templateBar();
        b.setProgressTintList(c(0xffffffff));
        NotificationProgressTone.apply(b, true, true);
        ColorStateList accent = c(0xff00a86b);
        b.setProgressTintList(accent);
        assertTrue(NotificationProgressTone.drift(b));
        assertFalse(NotificationProgressTone.apply(b, true, true));
        assertSame(accent, b.getProgressTintList());
        assertFalse(NotificationProgressTone.owns(b));
        assertFalse(NotificationProgressTone.apply(b, false, true));
        assertSame(accent, b.getProgressTintList());
    }

    @Test public void themeChangeRemapsFromTheOriginal() {
        ProgressBar b = templateBar();
        ColorStateList white = c(0xffffffff);
        b.setProgressTintList(white);
        NotificationProgressTone.apply(b, true, true);
        assertEquals(0xff000000, tint(b, NotificationProgressTone.PROGRESS));
        // White is not the dark theme's tone: the lease releases it back to the publisher's list.
        assertTrue(NotificationProgressTone.apply(b, true, false));
        assertSame(white, b.getProgressTintList());
        assertFalse(NotificationProgressTone.owns(b));
    }

    @Test public void outsideTemplateOrRowFailsClosed() {
        ProgressBar loose = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        loose.setId(android.R.id.progress);
        loose.setProgressTintList(c(0xffffffff));
        new FrameLayout(ctx).addView(loose);
        assertFalse(NotificationProgressTone.apply(loose, true, true));
        assertEquals(0xffffffff, loose.getProgressTintList().getDefaultColor());

        ProgressBar otherId = templateBar();
        otherId.setId(android.R.id.text1);
        otherId.setProgressTintList(c(0xffffffff));
        assertFalse(NotificationProgressTone.apply(otherId, true, true));
        assertEquals(0xffffffff, otherId.getProgressTintList().getDefaultColor());
    }

    @Test public void leavingTheRowRestores() {
        ProgressBar b = templateBar();
        ColorStateList white = c(0xffffffff);
        b.setProgressTintList(white);
        NotificationProgressTone.apply(b, true, true);
        ((android.view.ViewGroup) b.getParent()).removeView(b);
        new FrameLayout(ctx).addView(b);
        assertTrue(NotificationProgressTone.apply(b, true, true));
        assertSame(white, b.getProgressTintList());
    }

    @Test public void foregroundWalkTracksAndReportsRebind() {
        ProgressBar b = templateBar();
        ColorStateList track = c(0x4dffffff);
        b.setProgressBackgroundTintList(track);
        // drift() treats a bar outside a window as "detached"; attach the row for real.
        org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get().setContentView(row);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(b.isAttachedToWindow());
        List<android.view.View> tracked = new ArrayList<>();
        assertEquals(1, Foreground.apply(row, true, true, tracked));
        assertTrue(tracked.contains(b));
        assertTrue(Foreground.tracking());
        assertNull(Foreground.drift(tracked));

        b.setProgressBackgroundTintList(c(0x4dfafafa));
        assertEquals("progress-rebind", Foreground.drift(tracked));
        assertEquals(1, Foreground.apply(row, true, true, tracked));
        assertEquals(0x4d050505, tint(b, NotificationProgressTone.BACKGROUND));

        assertEquals(1, Foreground.apply(row, false, true, tracked));
        assertEquals(0x4dfafafa, tint(b, NotificationProgressTone.BACKGROUND));
        assertFalse(NotificationProgressTone.owns(b));
    }
}

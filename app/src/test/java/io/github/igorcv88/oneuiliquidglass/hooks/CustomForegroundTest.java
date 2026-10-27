package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
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
public final class CustomForegroundTest {
    // Foreground identifies SystemUI rows by class name, without linking hidden framework types.
    public static final class ExpandableNotificationRow extends FrameLayout {
        ExpandableNotificationRow(Context c) { super(c); }
    }
    private final Context context = RuntimeEnvironment.getApplication();
    private final Context publisher = new ContextWrapper(context) {
        @Override public String getPackageName() { return "br.com.drogasil"; }
    };
    private final ExpandableNotificationRow row = new ExpandableNotificationRow(context);
    private final FrameLayout content = new FrameLayout(context);
    private final ArrayList<android.view.View> tracked = new ArrayList<>();

    private TextView label(int color) {
        if (content.getParent() == null) row.addView(content);
        TextView text = new TextView(publisher);
        text.setTextColor(color);
        content.addView(text);
        return text;
    }
    @Test public void compactAndExpandedCustomTextFollowGlassAndRestore() {
        TextView title = label(0xfff4f4f6), body = label(0xffd5d7e0), step = label(0xffffffff);
        assertFalse(Foreground.standardNoId(context.getPackageName(), "com.android.systemui"));
        assertTrue(Foreground.customSurface(title));
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xff0b0b09, title.getCurrentTextColor());
        assertEquals(0xff2a281f, body.getCurrentTextColor());
        assertEquals(0xff000000, step.getCurrentTextColor());
        assertEquals(3, tracked.size());
        Foreground.apply(row, false, true, tracked);
        assertEquals(0xfff4f4f6, title.getCurrentTextColor());
        assertEquals(0xffd5d7e0, body.getCurrentTextColor());
        assertEquals(0xffffffff, step.getCurrentTextColor());
    }
    @Test public void privateBackgroundIsPreservedAndTransparentBackgroundQualifies() {
        TextView text = label(0xffffffff);
        content.setBackgroundColor(0xff203040);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xffffffff, text.getCurrentTextColor());
        assertTrue(tracked.isEmpty());
        content.setBackgroundColor(0x00000000);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xff000000, text.getCurrentTextColor());
        Foreground.apply(row, false, true, tracked);
    }
    @Test public void unknownBackgroundAndForegroundFailClosed() {
        TextView text = label(0xffffffff);
        content.setBackground(new GradientDrawable());
        assertFalse(Foreground.customSurface(text));
        content.setBackground(null);
        text.setForeground(new ColorDrawable(0xff203040));
        assertFalse(Foreground.customSurface(text));
        text.setForeground(null);
        assertTrue(Foreground.customSurface(text));
        content.removeView(text);
        assertFalse(Foreground.customSurface(text));
    }
    @Test public void newPrivateBackgroundRestoresTrackedText() {
        TextView text = label(0xffffffff);
        Foreground.apply(row, true, true, tracked);
        content.setBackgroundColor(0xff203040);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xffffffff, text.getCurrentTextColor());
        assertTrue(tracked.isEmpty());
    }
    @Test public void reapplyColorIsRedirectedAndNewOriginalIsRestored() {
        TextView text = label(0xffffffff);
        Foreground.apply(row, true, true, tracked);
        Integer next = Foreground.interceptColor(text, 0xffd5d7e0);
        assertEquals(Integer.valueOf(0xff2a281f), next);
        text.setTextColor(next);
        Foreground.apply(row, false, true, tracked);
        assertEquals(0xffd5d7e0, text.getCurrentTextColor());
    }
    @Test public void reapplyAddingPrivateBackgroundReleasesInterception() {
        TextView text = label(0xffffffff);
        Foreground.apply(row, true, true, tracked);
        content.setBackgroundColor(0xff203040);
        assertNull(Foreground.interceptColor(text, 0xffe0e0e0));
        text.setTextColor(0xffe0e0e0);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xffe0e0e0, text.getCurrentTextColor());
    }
    @Test public void accentsRemainAndNewInflatedTextIsIncluded() {
        TextView accent = label(0xff3d7bff);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xff3d7bff, accent.getCurrentTextColor());
        TextView replacement = label(0xffffffff);
        Foreground.apply(row, true, true, tracked);
        assertEquals(0xff000000, replacement.getCurrentTextColor());
        Foreground.apply(row, false, true, tracked);
    }
    @Test public void lightThemeTextFollowsDarkGlass() {
        TextView text = label(0xff202020);
        Foreground.apply(row, true, false, tracked);
        assertEquals(0xffdfdfdf, text.getCurrentTextColor());
        Foreground.apply(row, false, false, tracked);
        assertEquals(0xff202020, text.getCurrentTextColor());
    }
}

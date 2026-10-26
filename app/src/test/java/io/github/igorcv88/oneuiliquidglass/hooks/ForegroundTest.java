package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundTest {
    /** A view's colour state: what it draws, and an opaque saved form (a ColorStateList on device). */
    private static final class Fake implements Foreground.Ink {
        int color; final String own;
        Fake(int color) { this.color = color; this.own = "csl:" + Integer.toHexString(color); }
        @Override public Object key() { return this; }
        @Override public int color() { return color; }
        @Override public void set(int c) { color = c; }
        @Override public Object save() { return "csl:" + Integer.toHexString(color); }
        @Override public void restore(Object saved) { color = (int) Long.parseLong(((String) saved).substring(4), 16); }
    }

    @Test public void themeToneOnly() {
        assertTrue(Foreground.themeTone(0xffffffff, true));
        assertTrue(Foreground.themeTone(0xb3ffffff, true));
        assertTrue(Foreground.themeTone(0xffbdbdbd, true));
        assertFalse(Foreground.themeTone(0xff3d7bff, true));
        assertFalse(Foreground.themeTone(0xffffc864, true));
        assertFalse(Foreground.themeTone(0xff202020, true));
        assertTrue(Foreground.themeTone(0xff1a1a1a, false));
    }
    @Test public void mirrorKeepsAlphaAndHierarchy() {
        assertEquals(0xff000000, Foreground.mirror(0xffffffff));
        assertEquals(0xb3000000, Foreground.mirror(0xb3ffffff));
        assertEquals(0xff424242, Foreground.mirror(0xffbdbdbd));
    }
    /** Dark backdrop to light glass and back: the text gets exactly its own colour again. */
    @Test public void swapAndRestore() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake title = new Fake(0xffffffff), secondary = new Fake(0xb3ffffff), accent = new Fake(0xff3d7bff);
        assertTrue(Foreground.ink(title, true, true, m));
        assertTrue(Foreground.ink(secondary, true, true, m));
        assertFalse(Foreground.ink(accent, true, true, m));
        assertEquals(0xff000000, title.color);
        assertEquals(0xb3000000, secondary.color);
        assertEquals(0xff3d7bff, accent.color);
        // The 500 ms re-walk changes nothing.
        assertFalse(Foreground.ink(title, true, true, m));
        assertTrue(Foreground.ink(title, false, true, m));
        assertTrue(Foreground.ink(secondary, false, true, m));
        assertEquals(0xffffffff, title.color);
        assertEquals(0xb3ffffff, secondary.color);
        assertTrue(m.isEmpty());
        // release() and onPreDraw may both restore: the second is a no-op.
        assertFalse(Foreground.ink(title, false, true, m));
    }
    /** The notification was updated while swapped: its new colour is the original from then on. */
    @Test public void contentRebindIsTakenAsTheNewOriginal() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake body = new Fake(0xffffffff);
        Foreground.ink(body, true, true, m);
        body.color = 0xffe0e0e0; // RemoteViews applied again
        assertTrue(Foreground.ink(body, true, true, m));
        assertEquals(0xff1f1f1f, body.color);
        Foreground.ink(body, false, true, m);
        assertEquals(0xffe0e0e0, body.color);
        // Rebound to an accent colour: released, never restored over it.
        Foreground.ink(body, true, true, m);
        body.color = 0xff3d7bff;
        assertFalse(Foreground.ink(body, true, true, m));
        assertTrue(m.isEmpty());
        assertFalse(Foreground.ink(body, false, true, m));
        assertEquals(0xff3d7bff, body.color);
    }
    @Test public void restoreSkipsAViewWhoseContentChangedIt() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake body = new Fake(0xffffffff);
        Foreground.ink(body, true, true, m);
        body.color = 0xffd0d0d0;
        assertFalse(Foreground.ink(body, false, true, m));
        assertEquals(0xffd0d0d0, body.color);
        assertTrue(m.isEmpty());
    }
    @Test public void onlyStandardTemplateViewsAreEligible() {
        String host = "com.android.systemui";
        assertTrue(Foreground.standardId("android", host, null));
        assertTrue(Foreground.standardId(host, host, null));
        // A custom RemoteViews layout carries the app's ids.
        assertFalse(Foreground.standardId("com.whatsapp", host, null));
        assertFalse(Foreground.standardId(null, host, null));
        // Icons: only the expand chevron, never an app or action icon.
        assertTrue(Foreground.standardId("android", host, "expand_button_icon"));
        assertFalse(Foreground.standardId("android", host, "icon"));
        // The small icon has its own path (Foreground.glyph): only the template's android:icon.
        assertTrue(Foreground.smallIconId("android", "icon"));
        assertFalse(Foreground.smallIconId("android", "right_icon"));
        assertFalse(Foreground.smallIconId("com.whatsapp", "icon"));
        assertFalse(Foreground.standardId("android", host, "right_icon"));
        assertFalse(Foreground.standardId("com.whatsapp", host, "expand_more"));
    }
    /** SystemUI binds the content again while swapped: the next pre-draw sees it, not the 500 ms walk. */
    @Test public void aRebindIsSeenAsDrift() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake title = new Fake(0xffffffff);
        Foreground.ink(title, true, true, m);
        assertFalse(Foreground.drifted(title, m));
        title.color = 0xffffffff; // the rebind restored the theme's white
        assertTrue(Foreground.drifted(title, m));
        // The repair walk swaps it again and takes white as the original.
        assertTrue(Foreground.ink(title, true, true, m));
        assertFalse(Foreground.drifted(title, m));
        assertEquals(0xff000000, title.color);
        Foreground.ink(title, false, true, m);
        assertEquals(0xffffffff, title.color);
        // A view this class no longer tracks counts as drift (it must be walked again).
        assertTrue(Foreground.drifted(title, m));
    }
    /** SystemUI writes a colour on a swapped view: redirected in the same call, never fought a frame later. */
    @Test public void aColourWriteOnASwappedViewIsRedirected() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake title = new Fake(0xffffffff);
        Foreground.ink(title, true, true, m);
        // The theme's white again (rebind, animated group title): the mirrored tone is set instead.
        Integer to = Foreground.redirect(title, 0xffffffff, m, c -> "csl:" + Integer.toHexString(c));
        assertEquals(Integer.valueOf(0xff000000), to);
        // A light grey becomes the new original; restoring gives it back.
        to = Foreground.redirect(title, 0xffd0d0d0, m, c -> "csl:" + Integer.toHexString(c));
        assertEquals(Integer.valueOf(0xff2f2f2f), to);
        title.color = to;
        Foreground.ink(title, false, true, m);
        assertEquals(0xffd0d0d0, title.color);
    }
    @Test public void writesThatNeedNoRedirectPassThrough() {
        Map<Object, Foreground.Rec> m = new HashMap<>();
        Fake title = new Fake(0xffffffff), other = new Fake(0xffffffff);
        Foreground.ink(title, true, true, m);
        // Not swapped by this class.
        assertNull(Foreground.redirect(other, 0xffffffff, m, c -> c));
        // Already the swapped tone.
        assertNull(Foreground.redirect(title, 0xff000000, m, c -> c));
        // An accent colour: the view is released, the write goes through.
        assertNull(Foreground.redirect(title, 0xff3d7bff, m, c -> c));
        assertTrue(m.isEmpty());
    }
    /** A group's "+N" overflow text has no id: it qualifies when SystemUI inflated it, never in an app's layout. */
    @Test public void viewsWithoutIdQualifyOnlyFromSystemUi() {
        String host = "com.android.systemui";
        assertTrue(Foreground.standardNoId(host, host));
        assertFalse(Foreground.standardNoId("com.whatsapp", host));
        assertFalse(Foreground.standardNoId(host, null));
    }
}

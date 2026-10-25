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
        assertFalse(Foreground.standardId("android", host, "right_icon"));
        assertFalse(Foreground.standardId("com.whatsapp", host, "expand_more"));
    }
}

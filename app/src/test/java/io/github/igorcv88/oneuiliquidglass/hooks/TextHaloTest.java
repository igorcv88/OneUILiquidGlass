package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TextHaloTest {
    /** A text view's shadow state, as TextView keeps it. */
    private static final class Fake implements TextHalo.Glyphs {
        float radius, dx, dy; int shadow; final int text;
        Fake(int text) { this.text = text; }
        @Override public Object key() { return this; }
        @Override public float radius() { return radius; }
        @Override public float dx() { return dx; }
        @Override public float dy() { return dy; }
        @Override public int shadowColor() { return shadow; }
        @Override public int textColor() { return text; }
        @Override public void shadow(float r, float x, float y, int c) { radius = r; dx = x; dy = y; shadow = c; }
    }
    private static final int HALO = TextHalo.haloColor(0.45f, true);

    @Test public void onlyTheThemeTextToneGetsAHalo() {
        assertTrue(TextHalo.expectedTone(0xffffffff, true));
        assertTrue(TextHalo.expectedTone(0xffc8c8c8, true));
        assertFalse(TextHalo.expectedTone(0xff202020, true));
        // Accent text (app name, actions) keeps its own look.
        assertFalse(TextHalo.expectedTone(0xff3d7bff, true));
        assertFalse(TextHalo.expectedTone(0xffffc864, true));
        assertTrue(TextHalo.expectedTone(0xff1a1a1a, false));
        assertFalse(TextHalo.expectedTone(0xffffffff, false));
    }
    @Test public void haloColour() {
        assertEquals(0, TextHalo.haloColor(0f, true));
        assertEquals(0x73000000, TextHalo.haloColor(0.45f, true));
        assertEquals(0x73ffffff, TextHalo.haloColor(0.45f, false));
    }
    /** Glass to native background: removal gives back exactly what the view had. */
    @Test public void glassToNativeRestoresTheOriginalShadow() {
        Map<Object, TextHalo.Own> haloed = new HashMap<>();
        Fake title = new Fake(0xffffffff);
        title.dy = 0f; title.shadow = 0x12345678; // no radius: no visible shadow, but a colour to keep
        assertTrue(TextHalo.text(title, HALO, 8.75f, true, haloed));
        assertEquals(HALO, title.shadow);
        assertEquals(8.75f, title.radius, 0f);
        // The same halo again (the 500 ms re-walk) changes nothing.
        assertFalse(TextHalo.text(title, HALO, 8.75f, true, haloed));
        assertTrue(TextHalo.text(title, 0, 8.75f, true, haloed));
        assertEquals(0f, title.radius, 0f);
        assertEquals(0x12345678, title.shadow);
        assertTrue(haloed.isEmpty());
        // A second removal (release() and onPreDraw both remove) is a no-op.
        assertFalse(TextHalo.text(title, 0, 8.75f, true, haloed));
        assertEquals(0x12345678, title.shadow);
    }
    @Test public void viewsWithTheirOwnShadowOrAccentColourAreNeverTouched() {
        Map<Object, TextHalo.Own> haloed = new HashMap<>();
        Fake shadowed = new Fake(0xffffffff);
        shadowed.shadow(3f, 1f, 1f, 0xff000000);
        Fake accent = new Fake(0xff3d7bff);
        assertFalse(TextHalo.text(shadowed, HALO, 8.75f, true, haloed));
        assertFalse(TextHalo.text(accent, HALO, 8.75f, true, haloed));
        assertTrue(haloed.isEmpty());
        assertFalse(TextHalo.text(shadowed, 0, 8.75f, true, haloed));
        assertEquals(3f, shadowed.radius, 0f);
        assertEquals(0xff000000, shadowed.shadow);
    }
    /** A stronger halo after a new measurement still restores the shadow from before the first one. */
    @Test public void aChangedHaloKeepsTheFirstOriginal() {
        Map<Object, TextHalo.Own> haloed = new HashMap<>();
        Fake body = new Fake(0xffd0d0d0);
        TextHalo.text(body, HALO, 8.75f, true, haloed);
        assertTrue(TextHalo.text(body, TextHalo.haloColor(0.7f, true), 8.75f, true, haloed));
        TextHalo.text(body, 0, 8.75f, true, haloed);
        assertEquals(0f, body.radius, 0f);
        assertEquals(0, body.shadow);
    }
}

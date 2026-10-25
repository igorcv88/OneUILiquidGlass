package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TextHaloTest {
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
}

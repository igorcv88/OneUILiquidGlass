package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfIconInkTest {
    @Test public void shelfMirroringDoesNotDependOnSourceBitmap() {
        // In particular a preserved Samsung native small icon must not bypass the shelf tone.
        assertTrue(ShelfIconInk.shouldMirror(true, true));
        assertFalse(ShelfIconInk.shouldMirror(false, true));
        assertFalse(ShelfIconInk.shouldMirror(true, false));
    }
    @Test public void whiteSamsungInkOnLightShelfBecomesBlack() {
        assertEquals(0xff000000, ShelfIconInk.mirrored(true, 0, 0, 0, 0));
    }

    @Test public void blackSamsungInkOnDarkShelfBecomesWhite() {
        assertEquals(0xffffffff, ShelfIconInk.mirrored(false, 0, 0, 0, 0));
    }

    @Test public void fixedAndAdaptivePalettesAreRespected() {
        assertEquals(0xff112233, ShelfIconInk.mirrored(true, 1, 0xff112233, 0, 0));
        assertEquals(0xff445566, ShelfIconInk.mirrored(true, 2, 0, 0xff445566, 0xff778899));
        assertEquals(0xff778899, ShelfIconInk.mirrored(false, 2, 0, 0xff445566, 0xff778899));
    }

    @Test public void labelColorWinsOverStaleShadeDecision() {
        assertTrue(ShelfIconInk.darkText(0xff000000));
        assertTrue(ShelfIconInk.darkText(0xff505050));
        assertFalse(ShelfIconInk.darkText(0xffffffff));
        assertFalse(ShelfIconInk.darkText(0xff94b8ff));
        assertEquals(0xff000000, ShelfIconInk.mirrored(ShelfIconInk.darkText(0xff202020), 0, 0, 0, 0));
        assertEquals(0xffffffff, ShelfIconInk.mirrored(ShelfIconInk.darkText(0xffeeeeee), 0, 0, 0, 0));
    }
}

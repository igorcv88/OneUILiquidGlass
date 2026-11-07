package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShelfIconInkTest {
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
}

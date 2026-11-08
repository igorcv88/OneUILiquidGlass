package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ShelfIconInkTest {
    @Test public void automaticInkFollowsTheSharedSwapForBothThemes() {
        assertEquals(0xffffffff, ShelfIconInk.resolve(true, false, false, 0, 0, 0, 0));
        assertEquals(0xff000000, ShelfIconInk.resolve(true, true, true, 0, 0, 0, 0));
        assertEquals(0xff000000, ShelfIconInk.resolve(false, false, true, 0, 0, 0, 0));
        assertEquals(0xffffffff, ShelfIconInk.resolve(false, true, false, 0, 0, 0, 0));
    }
    @Test public void disabledTextSwapKeepsNativeInkEvenOnOppositeBackdrop() {
        assertEquals(0xffffffff, ShelfIconInk.resolve(true, false, true, 0, 0, 0, 0));
        assertEquals(0xff000000, ShelfIconInk.resolve(false, false, false, 0, 0, 0, 0));
    }
    @Test public void customInkUsesBackdropRatherThanNativeTextTone() {
        assertEquals(0xff123456, ShelfIconInk.resolve(true, false, true, 1,
                0xff123456, 0xff556677, 0xff8899aa));
        assertEquals(0xff556677, ShelfIconInk.resolve(true, false, true, 2,
                0xff123456, 0xff556677, 0xff8899aa));
        assertEquals(0xff8899aa, ShelfIconInk.resolve(false, false, false, 2,
                0xff123456, 0xff556677, 0xff8899aa));
    }
}

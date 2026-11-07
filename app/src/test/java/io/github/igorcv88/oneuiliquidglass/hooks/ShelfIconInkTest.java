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

    @Test public void onlyNotificationCountCanSupplyShelfInk() {
        assertTrue(ShelfIconInk.isCountLabel("shelf_notification_count", "3 more notifications"));
        assertTrue(ShelfIconInk.isCountLabel(null, "3 outras notificações"));
        assertTrue(ShelfIconInk.isCountLabel("shelf_count", "3 weitere"));
        assertFalse(ShelfIconInk.isCountLabel("noti_setting", "Notification settings"));
        assertFalse(ShelfIconInk.isCountLabel("noti_setting", "3 settings"));
        assertFalse(ShelfIconInk.isCountLabel("clear_all", "Clear"));
        assertFalse(ShelfIconInk.isCountLabel("app_name_text", "3 tickets"));
        assertFalse(ShelfIconInk.isCountLabel(null, null));
    }

    @Test public void automaticShelfInkCopiesRenderedLabelColorExactly() {
        assertEquals(0xff1c2430, ShelfIconInk.fromLabel(0xff1c2430, 0,
                0xff123456, 0xff556677, 0xff8899aa));
        assertEquals(0xffe0e6ef, ShelfIconInk.fromLabel(0xffe0e6ef, 0,
                0xff123456, 0xff556677, 0xff8899aa));
        assertEquals(0xff123456, ShelfIconInk.fromLabel(0xff1c2430, 1,
                0xff123456, 0xff556677, 0xff8899aa));
        assertEquals(0xff556677, ShelfIconInk.fromLabel(0xff1c2430, 2,
                0xff123456, 0xff556677, 0xff8899aa));
    }
}

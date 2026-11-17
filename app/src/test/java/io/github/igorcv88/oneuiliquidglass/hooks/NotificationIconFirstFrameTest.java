package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Laudo 17/11 section 6: a heads-up card whose own source lookup is still queued takes the answer
 * the status-bar icon of the same notification published for the same key, as soon as it is
 * published; nothing else is touched, and a card that stopped waiting is no longer indexed.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NotificationIconFirstFrameTest {
    private static NotificationIconTreatment.Resolved answer() {
        return new NotificationIconTreatment.Resolved(Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8), "officialMono", true);
    }

    @Test public void aWaitingCardAdoptsAPublishedSourceAndLeavesTheIndex() {
        NotificationIconTreatment card = new NotificationIconTreatment();
        card.pendForTest("pkg:1:7:icon");
        assertEquals(1, NotificationIconTreatment.waitingKeysForTest());
        NotificationIconTreatment.Resolved r = answer();
        NotificationIconTreatment.deliver("pkg:1:7:icon", r);
        assertFalse(card.pendingForTest());
        assertSame(r, card.resolvedForTest());
        assertEquals(0, NotificationIconTreatment.waitingKeysForTest());
    }

    @Test public void anotherKeyIsNotAdopted() {
        NotificationIconTreatment card = new NotificationIconTreatment();
        card.pendForTest("pkg:1:7:a");
        NotificationIconTreatment.deliver("pkg:1:7:b", answer());
        assertTrue(card.pendingForTest());
        assertNull(card.resolvedForTest());
        card.restore();
        assertEquals("restore unindexes the card", 0, NotificationIconTreatment.waitingKeysForTest());
    }

    @Test public void twoCardsOfTheSameKeyBothAdopt() {
        NotificationIconTreatment a = new NotificationIconTreatment(), b = new NotificationIconTreatment();
        a.pendForTest("k"); b.pendForTest("k");
        NotificationIconTreatment.Resolved r = answer();
        NotificationIconTreatment.deliver("k", r);
        assertSame(r, a.resolvedForTest());
        assertSame(r, b.resolvedForTest());
        assertEquals(0, NotificationIconTreatment.waitingKeysForTest());
    }
}

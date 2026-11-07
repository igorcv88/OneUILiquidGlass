package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationIconTreatmentTest {
    @Test public void smallTransparentSilhouetteQualifies() {
        int[] p = new int[100];
        for (int i = 0; i < 30; i++) p[i] = 0xffffffff;
        assertTrue(NotificationIconTreatment.silhouette(p));
    }

    @Test public void opaqueAndFullDiscAreRejected() {
        int[] opaque = new int[100];
        java.util.Arrays.fill(opaque, 0xffdd3333);
        assertFalse(NotificationIconTreatment.silhouette(opaque));
        int[] disc = new int[100];
        for (int i = 0; i < 80; i++) disc[i] = 0xffffffff;
        assertFalse(NotificationIconTreatment.silhouette(disc));
    }

    @Test public void onlyHighConfidenceOriginalsMatch() {
        int[] p = new int[24 * 24], q = new int[p.length];
        java.util.Arrays.fill(p, 0xffdddddd);
        java.util.Arrays.fill(q, 0xffdddddd);
        assertTrue(NotificationIconTreatment.sameOfficial(p, q));
        q[1] = 0xffcccccc;
        assertTrue(NotificationIconTreatment.sameOfficial(p, q));
        java.util.Arrays.fill(q, 0xffaa55ee);
        assertFalse(NotificationIconTreatment.sameOfficial(p, q));
    }
}

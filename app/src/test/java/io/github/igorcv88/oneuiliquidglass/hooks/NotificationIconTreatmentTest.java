package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationIconTreatmentTest {
    private static final class SamsungEntryRow {
        @SuppressWarnings("unused") private final Object mNotificationEntry = "samsung-entry";
        @SuppressWarnings("unused") private Object getEntry() { return null; }
    }
    private static final class GetterEntryRow {
        @SuppressWarnings("unused") private Object getNotificationEntry() { return "getter-entry"; }
    }

    @Test public void resolvesFirmwareEntryFallbacks() {
        assertEquals("samsung-entry", NotificationIconTreatment.findEntry(new SamsungEntryRow()));
        assertEquals("getter-entry", NotificationIconTreatment.findEntry(new GetterEntryRow()));
        assertNull(NotificationIconTreatment.findEntry(new Object()));
    }

    @Test public void recognizesSystemUiIconWithoutGlobalForegroundState() {
        assertTrue(Foreground.notificationIconCandidateId("com.android.systemui", "icon", "ImageView", "com.android.systemui"));
        assertTrue(Foreground.notificationIconCandidateId("com.android.systemui", "app_icon", "ImageView", "com.android.systemui"));
        assertTrue(Foreground.notificationIconCandidateId("android", "icon", "ImageView", "com.android.systemui"));
        assertTrue(Foreground.notificationIconCandidateId("com.android.systemui", "status_icon", "NotificationRowIconView", "com.android.systemui"));
    }

    @Test public void neverUsesAvatarMediaOrForeignIconViews() {
        assertFalse(Foreground.notificationIconCandidateId("com.android.systemui", "conversation_icon", "ImageView", "com.android.systemui"));
        assertFalse(Foreground.notificationIconCandidateId("com.android.systemui", "right_icon", "CachingIconView", "com.android.systemui"));
        assertFalse(Foreground.notificationIconCandidateId("com.example.app", "icon", "ImageView", "com.android.systemui"));
        assertFalse(Foreground.notificationIconCandidateId("com.android.systemui", "share_button", "ImageView", "com.android.systemui"));
    }

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

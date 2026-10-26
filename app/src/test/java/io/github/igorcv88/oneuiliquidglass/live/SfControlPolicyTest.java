package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class SfControlPolicyTest {
    private Map<String, String> valid() {
        return new HashMap<>(
                Map.of(
                        "helper",
                        "1",
                        "fingerprint_ok",
                        "1",
                        "bundled_ok",
                        "1",
                        "legacy_conflict",
                        "0",
                        "installed_ok",
                        "1",
                        "selftest_ok",
                        "1",
                        "update_needed",
                        "0"));
    }

    @Test
    public void permissionBundleAndBusyAlwaysGatePrivilegedButtons() {
        for (int bits = 0; bits < 8; bits++) {
            boolean root = (bits & 1) != 0, bundle = (bits & 2) != 0, busy = (bits & 4) != 0;
            assertEquals(
                    root && bundle && !busy, SfControlPolicy.prepare(root, bundle, busy, valid()));
            assertEquals(
                    root && bundle && !busy, SfControlPolicy.restart(root, bundle, busy, valid()));
        }
    }

    @Test
    public void missingOrInvalidHelperCompatibilityBlocksPreparation() {
        for (String key : List.of("helper", "fingerprint_ok", "bundled_ok", "legacy_conflict")) {
            Map<String, String> s = valid();
            s.remove(key);
            assertFalse(key, SfControlPolicy.prepare(true, true, false, s));
            s.put(key, key.equals("legacy_conflict") ? "1" : "0");
            assertFalse(key, SfControlPolicy.prepare(true, true, false, s));
        }
        assertFalse(SfControlPolicy.prepare(true, true, false, Collections.emptyMap()));
    }

    @Test
    public void restartRequiresCurrentFilesAndPassingSelftest() {
        for (String key : List.of("installed_ok", "selftest_ok", "update_needed")) {
            Map<String, String> s = valid();
            s.remove(key);
            assertTrue(SfControlPolicy.prepare(true, true, false, s));
            assertFalse(key, SfControlPolicy.restart(true, true, false, s));
            s.put(key, key.equals("update_needed") ? "1" : "0");
            assertFalse(key, SfControlPolicy.restart(true, true, false, s));
        }
    }
}

package io.github.igorcv88.oneuiliquidglass.live;

import java.util.Map;

/** The UI must fail closed until both UID 0 and a valid privileged helper reply exist. */
public final class SfControlPolicy {
    private SfControlPolicy() {}

    public static boolean prepare(
            boolean root, boolean bundled, boolean busy, Map<String, String> s) {
        return root
                && bundled
                && !busy
                && "1".equals(s.get("helper"))
                && "1".equals(s.get("fingerprint_ok"))
                && "1".equals(s.get("bundled_ok"))
                && "0".equals(s.get("legacy_conflict"));
    }

    public static boolean restart(
            boolean root, boolean bundled, boolean busy, Map<String, String> s) {
        return prepare(root, bundled, busy, s)
                && "1".equals(s.get("installed_ok"))
                && "1".equals(s.get("selftest_ok"))
                && "0".equals(s.get("update_needed"));
    }
}

package io.github.igorcv88.oneuiliquidglass.live;

import java.util.Arrays;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SfLiveTest {
    private static final String CURRENT = "helper=1\nfingerprint_ok=1\nupdate_needed=0\nselftest_ok=1\nsf_loaded=1\n";

    @Test public void parsesKeyValueLinesAndSkipsTheRest() {
        Map<String, String> m = SfLive.parse("# comment\nversion=v0.21.0\n\nnoise\n=bad\nsha256_lib=ab=cd\n");
        assertEquals("v0.21.0", m.get("version"));
        assertEquals("ab=cd", m.get("sha256_lib"));
        assertEquals(2, m.size());
    }
    @Test public void statesInTheOrderTheButtonResolvesThem() {
        assertEquals(SfLive.State.NO_ROOT, SfLive.state(SfLive.parse("")));
        assertEquals(SfLive.State.UNSUPPORTED, SfLive.state(SfLive.parse(CURRENT.replace("fingerprint_ok=1", "fingerprint_ok=0"))));
        assertEquals(SfLive.State.UPDATE, SfLive.state(SfLive.parse(CURRENT.replace("update_needed=0", "update_needed=1"))));
        assertEquals(SfLive.State.SELFTEST, SfLive.state(SfLive.parse(CURRENT.replace("selftest_ok=1", "selftest_ok=0"))));
        assertEquals(SfLive.State.RESTART, SfLive.state(SfLive.parse(CURRENT.replace("sf_loaded=1", "sf_loaded=0"))));
        assertEquals(SfLive.State.LOADED, SfLive.state(SfLive.parse(CURRENT)));
    }
    @Test public void aMissingKeyNeverReadsAsDone() {
        assertEquals(SfLive.State.UPDATE, SfLive.state(SfLive.parse("helper=1\nfingerprint_ok=1\n")));
    }
    @Test public void commandsAreFixed() {
        assertEquals(Arrays.asList("su", "-c", "sh '/data/user/0/x/files/sfhook/oulg-live.sh' status"),
                SfLive.command("/data/user/0/x/files/sfhook", SfLive.Command.STATUS, null));
        assertEquals("sh '/d/oulg-live.sh' restart rewrite", SfLive.command("/d", SfLive.Command.RESTART, "rewrite").get(2));
        assertThrows(IllegalArgumentException.class, () -> SfLive.command("/d", SfLive.Command.RESTART, "now; reboot"));
        assertThrows(IllegalArgumentException.class, () -> SfLive.command("/d", SfLive.Command.STATUS, "x"));
        assertThrows(IllegalArgumentException.class, () -> SfLive.command("/d'; reboot; '", SfLive.Command.STATUS, null));
        assertThrows(IllegalArgumentException.class, () -> SfLive.command("relative", SfLive.Command.STATUS, null));
    }
}

package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.config.Mode;
import org.junit.Test;

public class UserspaceRestartPolicyTest {
    @Test public void activeLensAlwaysRequiresItsInjector() {
        assertEquals(UserspaceRestartPolicy.Route.REINJECT_LENS,
                UserspaceRestartPolicy.choose(true, Mode.DYNAMIC_LENS, true));
        assertEquals(UserspaceRestartPolicy.Route.BLOCKED,
                UserspaceRestartPolicy.choose(true, Mode.DYNAMIC_LENS, false));
    }

    @Test public void unknownPropertiesNeverSelectPlainRestart() {
        assertEquals(UserspaceRestartPolicy.Route.BLOCKED,
                UserspaceRestartPolicy.choose(false, Mode.STANDARD, true));
        assertEquals(UserspaceRestartPolicy.Route.BLOCKED,
                UserspaceRestartPolicy.choose(false, Mode.DYNAMIC_LENS, true));
    }

    @Test public void plainRestartIsOnlyForNonLensMode() {
        assertEquals(UserspaceRestartPolicy.Route.PLAIN,
                UserspaceRestartPolicy.choose(true, Mode.STANDARD, false));
        assertEquals(UserspaceRestartPolicy.Route.PLAIN,
                UserspaceRestartPolicy.choose(true, Mode.CAPTURE, false));
        assertTrue(RootAccess.SOFT_REBOOT.contains("setprop debug.oulg.sfrefract"));
        assertFalse(RootAccess.SOFT_REBOOT.contains("keep=1"));
    }
}

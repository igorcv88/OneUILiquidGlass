package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertEquals;

import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Decision;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Input;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Reason;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** {@link EnergyPolicy.Config#from} reads the live Tuning, which needs the Android runtime. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class EnergyPolicyConfigTest {
    @Test public void masterOffDisablesThePolicy() {
        Tuning t = Tuning.get();
        assertEquals(0, EnergyPolicy.Config.from(t, false).surfaces);
        assertEquals(EnergyPolicy.ALL_SURFACES, EnergyPolicy.Config.from(t, true).surfaces);
        assertEquals(Reason.NONE, EnergyPolicy.decide(EnergyPolicy.Config.from(t, false), new Input(1, true, false, true), Decision.NONE).reason);
    }

    @Test public void defaultsNeverPauseAnything() {
        EnergyPolicy.Config c = EnergyPolicy.Config.from(Tuning.get(), true);
        assertEquals(0, c.mode);
        assertEquals(false, c.followSaver);
        assertEquals(Reason.NONE, EnergyPolicy.decide(c, new Input(1, true, false, true), Decision.NONE).reason);
    }
}

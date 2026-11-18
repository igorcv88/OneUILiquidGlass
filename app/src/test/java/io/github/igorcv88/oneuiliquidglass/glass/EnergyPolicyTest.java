package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Config;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Decision;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Input;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy.Reason;
import org.junit.Test;

/** The transition table of docs/OULG_UI_PLAN/ENERGY_POLICY.md section 4 (limit 20, hysteresis 5). */
public class EnergyPolicyTest {
    private static final int ALL = 127;

    private static Config config(int mode, boolean keepCharging, boolean followSaver, int surfaces) {
        return new Config(mode, 20, 5, surfaces, keepCharging, followSaver);
    }

    private static Config threshold(boolean keepCharging) {
        return config(1, keepCharging, false, ALL);
    }

    private static Decision low() {
        return new Decision(Reason.LOW_BATTERY, ALL);
    }

    private static void check(Reason reason, int mask, Config c, Input in, Decision previous) {
        Decision d = EnergyPolicy.decide(c, in, previous);
        assertEquals(reason, d.reason);
        assertEquals(mask, d.suspendedMask);
    }

    @Test public void equalityToTheLimitSuspends() {
        check(Reason.NONE, 0, threshold(true), new Input(21, true, false, false), Decision.NONE);
        check(Reason.LOW_BATTERY, ALL, threshold(true), new Input(20, true, false, false), Decision.NONE);
    }

    @Test public void hysteresisKeepsTheGlassPausedUntilLimitPlusMargin() {
        check(Reason.LOW_BATTERY, ALL, threshold(true), new Input(24, true, false, false), low());
        check(Reason.NONE, 0, threshold(true), new Input(25, true, false, false), low());
    }

    @Test public void chargingKeepsTheGlassOnlyWhenAsked() {
        check(Reason.NONE, 0, threshold(true), new Input(10, true, true, false), low());
        check(Reason.LOW_BATTERY, ALL, threshold(false), new Input(10, true, true, false), low());
    }

    @Test public void batterySaverDoesNotChangeTheGlassByDefault() {
        check(Reason.NONE, 0, threshold(true), new Input(80, true, false, true), Decision.NONE);
    }

    @Test public void followSaverSuspendsEvenWhileChargingAndWithModeNever() {
        check(Reason.SAVER, ALL, config(1, true, true, ALL), new Input(80, true, true, true), Decision.NONE);
        check(Reason.SAVER, ALL, config(0, true, true, ALL), new Input(80, true, false, true), Decision.NONE);
    }

    @Test public void followSaverWithoutSaverFallsBackToThePolicy() {
        check(Reason.NONE, 0, config(1, true, true, ALL), new Input(80, true, false, false), Decision.NONE);
        check(Reason.LOW_BATTERY, ALL, config(1, true, true, ALL), new Input(10, true, false, false), Decision.NONE);
    }

    @Test public void unknownBatteryNeverSuspends() {
        check(Reason.NONE, 0, threshold(true), new Input(-1, true, false, false), Decision.NONE);
        check(Reason.NONE, 0, threshold(true), new Input(10, false, false, false), low());
        check(Reason.NONE, 0, threshold(true), new Input(-1, true, false, false), low());
    }

    @Test public void modeNeverDoesNothingForLowBattery() {
        check(Reason.NONE, 0, config(0, true, false, ALL), new Input(10, true, false, false), Decision.NONE);
    }

    @Test public void emptySurfaceMaskIsAlwaysNone() {
        check(Reason.NONE, 0, config(1, true, true, 0), new Input(5, true, false, true), Decision.NONE);
        check(Reason.NONE, 0, config(1, true, false, 0), new Input(5, true, false, false), low());
    }

    @Test public void suspendedMaskIsExactlyTheChosenSurfaces() {
        Decision d = EnergyPolicy.decide(config(1, true, false, 1 | 16), new Input(10, true, false, false), Decision.NONE);
        assertEquals(Reason.LOW_BATTERY, d.reason);
        assertEquals(17, d.suspendedMask);
    }

    @Test public void changingTheConfigReevaluatesImmediately() {
        Input in = new Input(15, true, false, false);
        Decision paused = EnergyPolicy.decide(threshold(true), in, Decision.NONE);
        assertEquals(Reason.LOW_BATTERY, paused.reason);
        // Policy turned off: resumes at once, even though the level is still low.
        assertEquals(Reason.NONE, EnergyPolicy.decide(config(0, true, false, ALL), in, paused).reason);
        // Narrower surface set: the mask follows the new config.
        assertEquals(2, EnergyPolicy.decide(config(1, true, false, 2), in, paused).suspendedMask);
    }

    @Test public void noneDecisionIsShared() {
        assertSame(Decision.NONE, EnergyPolicy.decide(threshold(true), new Input(80, true, false, false), Decision.NONE));
    }
}

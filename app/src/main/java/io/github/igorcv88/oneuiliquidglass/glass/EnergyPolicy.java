package io.github.igorcv88.oneuiliquidglass.glass;

/**
 * User-controlled energy policy (docs/OULG_UI_PLAN/ENERGY_POLICY.md section 4). Pure and clockless:
 * the previous decision is the only memory (hysteresis). Nothing here lowers the cost of the glass;
 * the one action is to pause the glass on the surfaces the user chose, restoring Samsung's native
 * look through the same path as turning a surface off.
 */
public final class EnergyPolicy {
    public static final int BIT_HEADSUP = 1, BIT_KEYGUARD = 2, BIT_SHADE = 4, BIT_QUICK_SETTINGS = 8,
            BIT_LIVE_ACTIVITY = 16, BIT_NOW_BAR = 32, BIT_TOAST = 64;
    public static final int ALL_SURFACES = 127;

    public enum Reason { NONE, SAVER, LOW_BATTERY }

    /** Battery state; {@code level} is 0..100 or -1 when unknown. */
    public static final class Input {
        public final int level;
        public final boolean present, charging, saver;

        public Input(int level, boolean present, boolean charging, boolean saver) {
            this.level = level;
            this.present = present;
            this.charging = charging;
            this.saver = saver;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Input)) return false;
            Input i = (Input) o;
            return level == i.level && present == i.present && charging == i.charging && saver == i.saver;
        }

        @Override public int hashCode() {
            return ((level * 31 + (present ? 1 : 0)) * 31 + (charging ? 1 : 0)) * 31 + (saver ? 1 : 0);
        }
    }

    public static final class Config {
        public final int mode, threshold, hysteresis, surfaces;
        public final boolean keepWhileCharging, followSaver;

        public Config(int mode, int threshold, int hysteresis, int surfaces, boolean keepWhileCharging,
                boolean followSaver) {
            this.mode = mode;
            this.threshold = threshold;
            this.hysteresis = hysteresis;
            this.surfaces = surfaces;
            this.keepWhileCharging = keepWhileCharging;
            this.followSaver = followSaver;
        }

        /** The master switch off leaves no surface to pause. */
        public static Config from(Tuning t, boolean master) {
            return new Config(t.energyMode, t.energyThreshold, t.energyHysteresis,
                    master ? t.energySurfaces : 0, t.energyCharging, t.energySaver);
        }

        /**
         * The same config read from the provider snapshot the SystemUI process holds, clamped to the
         * registry domains. Absent keys take the registry defaults (never pause anything).
         */
        public static Config fromSnapshot(java.util.function.Function<String, String> get, boolean master) {
            int surfaces = number(get, "energysurfaces", ALL_SURFACES, 1, ALL_SURFACES);
            return new Config(number(get, "energymode", 0, 0, 1), number(get, "energythreshold", 20, 5, 50),
                    number(get, "energyhysteresis", 5, 2, 15), master ? surfaces : 0,
                    !"0".equals(get.apply("energycharging")), "1".equals(get.apply("energysaver")));
        }

        private static int number(java.util.function.Function<String, String> get, String key, int fallback,
                int min, int max) {
            String raw = get.apply(key);
            if (raw == null) return fallback;
            try {
                return Math.max(min, Math.min(max, Math.round(Float.parseFloat(raw.trim()))));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Config)) return false;
            Config c = (Config) o;
            return mode == c.mode && threshold == c.threshold && hysteresis == c.hysteresis
                    && surfaces == c.surfaces && keepWhileCharging == c.keepWhileCharging
                    && followSaver == c.followSaver;
        }

        @Override public int hashCode() {
            return (((mode * 31 + threshold) * 31 + hysteresis) * 31 + surfaces) * 31
                    + (keepWhileCharging ? 2 : 0) + (followSaver ? 1 : 0);
        }
    }

    public static final class Decision {
        public static final Decision NONE = new Decision(Reason.NONE, 0);
        public final Reason reason;
        public final int suspendedMask;

        public Decision(Reason reason, int suspendedMask) {
            this.reason = reason;
            this.suspendedMask = suspendedMask;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Decision && ((Decision) o).reason == reason && ((Decision) o).suspendedMask == suspendedMask;
        }

        @Override public int hashCode() {
            return reason.hashCode() * 131 + suspendedMask;
        }
    }

    /** Rules in order of precedence; see the table in ENERGY_POLICY.md section 4. */
    public static Decision decide(Config c, Input in, Decision previous) {
        if (c.surfaces == 0) return Decision.NONE;
        if (c.followSaver && in.saver) return new Decision(Reason.SAVER, c.surfaces);
        if (c.mode == 0) return Decision.NONE;
        if (!in.present || in.level < 0) return Decision.NONE;
        if (in.charging && c.keepWhileCharging) return Decision.NONE;
        boolean wasLow = previous != null && previous.reason == Reason.LOW_BATTERY;
        boolean low = wasLow ? in.level < c.threshold + c.hysteresis : in.level <= c.threshold;
        return low ? new Decision(Reason.LOW_BATTERY, c.surfaces) : Decision.NONE;
    }

    private EnergyPolicy() {}
}

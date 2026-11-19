package io.github.igorcv88.oneuiliquidglass.config.state;

import android.content.SharedPreferences;

import io.github.igorcv88.oneuiliquidglass.live.CompositorValues;

/**
 * Whether the person means to use the dynamic lens (docs/OULG_UI_PLAN/
 * ACTIVATION_AND_BACKUP_CONTRACT.md section 2). A lens that is installed but not loaded is a
 * transient unavailability, not a reason to treat its controls as incompatible: with intent, the
 * rules of the next compile (a restart always loads the lens) decide what can be edited. The
 * intent is kept in the app's own preferences; the helper-owned {@code sfrefract} is never saved.
 */
public final class LensIntent {
    public static final String PREF = "lens.intent";

    private LensIntent() {}

    /**
     * @param lensLoaded the lens is running now (observed {@code sfrefract} is 1)
     * @param stored the saved intent, null when the app never recorded one (an upgrade)
     * @param hasCompileHistory a compile or a restart attempt was recorded before
     */
    public static boolean of(boolean lensLoaded, Boolean stored, boolean hasCompileHistory) {
        if (lensLoaded) return true;
        if (stored != null) return stored;
        return hasCompileHistory;
    }

    public static boolean of(ConfirmedSnapshot snapshot, SharedPreferences lensPrefs) {
        Boolean stored = lensPrefs.contains(PREF) ? lensPrefs.getBoolean(PREF, false) : null;
        boolean history = CompositorValues.hasCompiled(lensPrefs)
                || !CompositorValues.attemptValues(lensPrefs).isEmpty();
        return of(snapshot.lensLoaded(), stored, history);
    }

    /** commit(): a restart or the lens removal follows and may end this process first. */
    @android.annotation.SuppressLint("ApplySharedPref")
    public static void record(SharedPreferences lensPrefs, boolean on) {
        lensPrefs.edit().putBoolean(PREF, on).commit();
    }
}

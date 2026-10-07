package io.github.igorcv88.oneuiliquidglass;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** Module configuration shared by the app UI and the config provider SystemUI queries. */
public final class Config {
    private Config() {}
    public static final String PACKAGE = "io.github.igorcv88.oneuiliquidglass";
    public static final String AUTHORITY = PACKAGE + ".config";
    public static final String PREFS = "glass";
    public static final String KEY_ENABLED = "enabled";

    /** True when LSPosed redirected the preferences so SystemUI can read them via XSharedPreferences. */
    public static boolean lsposedPrefs;

    /**
     * LSPosed (xposedsharedprefs) only redirects MODE_WORLD_READABLE files to a SystemUI-readable
     * location; without its hook the framework throws SecurityException and the file stays private.
     * SystemUI then reads the value through {@link ConfigProvider} instead.
     */
    @SuppressWarnings("deprecation")
    @SuppressLint("WorldReadableFiles")
    public static SharedPreferences open(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
            lsposedPrefs = true;
            return prefs;
        } catch (SecurityException e) {
            lsposedPrefs = false;
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }
}

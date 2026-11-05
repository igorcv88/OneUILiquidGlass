package io.github.igorcv88.oneuiliquidglass.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/** App-only language preference. This never changes the SystemUI configuration. */
public final class AppLanguage {
    private static final String PREFERENCES = "ui";
    private static final String KEY = "language";
    public static final String ENGLISH = "en";
    public static final String PORTUGUESE = "pt-BR";

    private AppLanguage() {}

    public static String normalize(String value) {
        return PORTUGUESE.equals(value) ? PORTUGUESE : ENGLISH;
    }

    public static String get(Context context) {
        return normalize(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY, ENGLISH));
    }

    public static void set(Context context, String value) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY, normalize(value)).apply();
    }

    /** Creates a locale-scoped context without altering the device or process default locale. */
    public static Context wrap(Context context) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(get(context))));
        return context.createConfigurationContext(configuration);
    }
}

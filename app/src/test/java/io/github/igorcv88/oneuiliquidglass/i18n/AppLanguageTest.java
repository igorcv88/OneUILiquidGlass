package io.github.igorcv88.oneuiliquidglass.i18n;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.R;
import android.content.res.Configuration;
import android.os.LocaleList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppLanguageTest {
    private Context context;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void englishIsDefaultEvenWhenDeviceIsPortuguese() {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag("pt-BR")));
        Context portugueseDevice = context.createConfigurationContext(config);
        assertEquals("en", AppLanguage.wrap(portugueseDevice).getResources()
                .getConfiguration().getLocales().get(0).toLanguageTag());
    }

    @Test public void selectionPersistsAndDoesNotChangeOtherPreferences() {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("reduceMotion", true).commit();
        AppLanguage.set(context, AppLanguage.PORTUGUESE);
        Context recreated = AppLanguage.wrap(context);
        assertEquals("pt-BR", recreated.getResources().getConfiguration().getLocales().get(0).toLanguageTag());
        assertEquals(true, recreated.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("reduceMotion", false));
        AppLanguage.set(recreated, AppLanguage.ENGLISH);
        assertEquals("en", AppLanguage.get(context));
    }
    @Test public void homeLabelsAndFormattedMessagesFollowSelectedLanguage() {
        Context en = AppLanguage.wrap(context);
        assertEquals("Glass on Lock screen", en.getString(R.string.home_surface_glass,
                en.getString(R.string.surface_lock)));
        assertEquals("Active on 2 of 3 surfaces", en.getString(R.string.home_active_count, 2));
        assertEquals("3 settings changed outside the app.", en.getString(R.string.home_conflict_many, 3));

        AppLanguage.set(context, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(context);
        assertEquals("Vidro em Bloqueio", pt.getString(R.string.home_surface_glass,
                pt.getString(R.string.surface_lock)));
        assertEquals("Ativo em 2 de 3 ambientes", pt.getString(R.string.home_active_count, 2));
        assertEquals("3 ajustes foram alterados fora do app.", pt.getString(R.string.home_conflict_many, 3));
    }

}

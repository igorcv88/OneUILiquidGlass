package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PresetReviewLocalizationTest {
    @Test public void optInSurfacesHaveCorrectNamesInBothLanguages() {
        Context base = RuntimeEnvironment.getApplication();
        AppLanguage.set(base, AppLanguage.ENGLISH);
        Context en = AppLanguage.wrap(base);
        assertEquals("Quick Settings glass", PresetsSheet.PresetReview.flagName(en, ProductFlags.QUICK_SETTINGS));
        assertEquals("Glass on Now Bar", PresetsSheet.PresetReview.flagName(en, ProductFlags.NOW_BAR));
        assertEquals("Glass on Live updates", PresetsSheet.PresetReview.flagName(en, ProductFlags.LIVE_ACTIVITY));
        AppLanguage.set(base, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(base);
        assertEquals("Vidro da Central de controle", PresetsSheet.PresetReview.flagName(pt, ProductFlags.QUICK_SETTINGS));
        assertEquals("Vidro em Now Bar", PresetsSheet.PresetReview.flagName(pt, ProductFlags.NOW_BAR));
        assertEquals("Vidro em Atividade ao vivo", PresetsSheet.PresetReview.flagName(pt, ProductFlags.LIVE_ACTIVITY));
    }
}

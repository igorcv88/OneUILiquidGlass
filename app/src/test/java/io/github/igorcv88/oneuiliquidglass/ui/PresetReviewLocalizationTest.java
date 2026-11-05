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
    @Test public void importErrorsIdentifyTheCauseInBothLanguages() throws Exception {
        Context base = RuntimeEnvironment.getApplication();
        String[] inputs = {"{\"schemaVersion\":2}", "{\"schemaVersion\":1,\"enabled\":true,\"desired\":{\"mediaart\":25}}", "{\"schemaVersion\":1,\"enabled\":true,\"desired\":{\"mediaart\":\"bad\"}}", "{"};
        String[][] expected = {{"This preset version is incompatible.", "The value for mediaart must be text.", "The value for mediaart is invalid."}, {"A versão deste preset é incompatível.", "O valor de mediaart deve ser texto.", "O valor de mediaart é inválido."}};
        int index = 0;
        for (String language : new String[] {AppLanguage.ENGLISH, AppLanguage.PORTUGUESE}) {
            AppLanguage.set(base, language);
            Context context = AppLanguage.wrap(base);
            for (int n = 0; n < 3; n++) {
                try { io.github.igorcv88.oneuiliquidglass.config.PresetCodec.parse(inputs[n]); org.junit.Assert.fail(); }
                catch (Exception e) { assertEquals(expected[index][n], PresetsSheet.PresetReview.importError(context, e)); }
            }
            try { io.github.igorcv88.oneuiliquidglass.config.PresetCodec.parse("x".repeat(io.github.igorcv88.oneuiliquidglass.config.PresetCodec.MAX_BYTES + 1)); org.junit.Assert.fail(); }
            catch (Exception e) { assertEquals(index == 0 ? "The preset file is too large." : "O arquivo de preset é muito grande.", PresetsSheet.PresetReview.importError(context, e)); }
            try { io.github.igorcv88.oneuiliquidglass.config.PresetCodec.parse(inputs[3]); org.junit.Assert.fail(); }
            catch (Exception e) { assertEquals(context.getString(io.github.igorcv88.oneuiliquidglass.R.string.preset_invalid), PresetsSheet.PresetReview.importError(context, e)); }
            index++;
        }
    }
}

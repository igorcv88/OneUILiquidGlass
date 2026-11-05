package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UiLabelsTest {
    @Test public void sectionsLayersAndPreviewUseTheAppLanguage() {
        Context base = RuntimeEnvironment.getApplication();
        AppLanguage.set(base, AppLanguage.ENGLISH);
        Context en = AppLanguage.wrap(base);
        assertEquals("Glass and blur", UiLabels.section(en, Controls.Section.GLASS));
        assertEquals("Per-card blur", UiLabels.layer(en, "per_card_blur"));
        assertEquals("Grid", UiLabels.backdrop(en, NotificationPreview.Backdrop.GRID));
        assertEquals("2 settings", UiLabels.count(en, 2));
        assertEquals("Default: 50 %", en.getString(R.string.control_default, "50 %"));
        assertEquals("Saved in app: 50 %", en.getString(R.string.control_saved_in_app, "50 %"));
        for (Controls.Control control : Controls.all()) {
            assertNotEquals("Missing title resource for " + control.key, 0, ControlTitles.resource(control));
            if (control.autoLabel != null) assertNotEquals(control.key, 0, ControlOptions.resource(control.autoLabel));
            if (control.offLabel != null) assertNotEquals(control.key, 0, ControlOptions.resource(control.offLabel));
            for (String[] option : control.choices.values()) for (String text : option)
                if (!text.isEmpty()) assertNotEquals(control.key + ": " + text, 0, ControlOptions.resource(text));
        }
        assertEquals("Native artwork", ControlValues.display(en, Controls.get("mediaart"), "0"));
        assertEquals("Automatic", ControlValues.display(en, Controls.get("kgblurradius"), "-1"));
        assertEquals("Adaptive", ControlValues.display(en, Controls.get("shadetone"), "3"));
        assertTrue(ControlDescriptions.hint(en, Controls.get("mediaart")).contains("without turning off glass"));
        assertEquals("Blur", ControlTitles.title(en, Controls.get("liveradius")));
        assertEquals("Media artwork opacity", ControlTitles.title(en, Controls.get("mediaart")));
        assertTrue(SearchSheet.matchesControl(en, Controls.get("mediaart"), "artwork opacity"));
        assertTrue(SearchSheet.matchesControl(en, Controls.get("liveradius"), "glass and blur"));
        assertTrue(SearchSheet.matchesControl(en, Controls.get("liveradius"), "LIVERADIUS"));
        AppLanguage.set(base, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(base);
        assertEquals("Vidro e desfoque", UiLabels.section(pt, Controls.Section.GLASS));
        assertEquals("Desfoque por cartão", UiLabels.layer(pt, "per_card_blur"));
        assertEquals("Grade", UiLabels.backdrop(pt, NotificationPreview.Backdrop.GRID));
        assertEquals("2 ajustes", UiLabels.count(pt, 2));
        assertEquals("Padrão: 50 %", pt.getString(R.string.control_default, "50 %"));
        assertEquals("Salvo no aplicativo: 50 %", pt.getString(R.string.control_saved_in_app, "50 %"));
        assertEquals("Capa nativa", ControlValues.display(pt, Controls.get("mediaart"), "0"));
        assertEquals("Automático", ControlValues.display(pt, Controls.get("kgblurradius"), "-1"));
        assertEquals("Adaptativa", ControlValues.display(pt, Controls.get("shadetone"), "3"));
        assertTrue(ControlDescriptions.hint(pt, Controls.get("mediaart")).contains("sem desligar o vidro"));
        assertEquals("Desfoque", ControlTitles.title(pt, Controls.get("liveradius")));
        assertEquals("Opacidade da capa de mídia", ControlTitles.title(pt, Controls.get("mediaart")));
        assertTrue(SearchSheet.matchesControl(pt, Controls.get("mediaart"), "capa de midia"));
        assertTrue(SearchSheet.matchesControl(pt, Controls.get("liveradius"), "VIDRO E DESFOQUE"));
        assertTrue(SearchSheet.matchesControl(pt, Controls.get("liveradius"), "DESFOQUE"));
    }
}

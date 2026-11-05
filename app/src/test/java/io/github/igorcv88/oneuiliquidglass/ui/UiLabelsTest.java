package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
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
        assertTrue(SearchSheet.matchesControl(en, Controls.get("liveradius"), "glass and blur"));
        assertTrue(SearchSheet.matchesControl(en, Controls.get("liveradius"), "LIVERADIUS"));
        AppLanguage.set(base, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(base);
        assertEquals("Vidro e desfoque", UiLabels.section(pt, Controls.Section.GLASS));
        assertEquals("Desfoque por cartão", UiLabels.layer(pt, "per_card_blur"));
        assertEquals("Grade", UiLabels.backdrop(pt, NotificationPreview.Backdrop.GRID));
        assertEquals("2 ajustes", UiLabels.count(pt, 2));
        assertTrue(SearchSheet.matchesControl(pt, Controls.get("liveradius"), "VIDRO E DESFOQUE"));
        assertTrue(SearchSheet.matchesControl(pt, Controls.get("liveradius"), "DESFOQUE"));
    }
}

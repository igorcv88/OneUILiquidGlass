package io.github.igorcv88.oneuiliquidglass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class TestNotificationLocalizationTest {
    @Test public void notificationAndOutcomeFollowAppLanguage() {
        Context base = RuntimeEnvironment.getApplication();
        AppLanguage.set(base, AppLanguage.ENGLISH);
        Context en = AppLanguage.wrap(base);
        assertEquals("Test notification", TestNotificationModel.title(en));
        assertEquals("Open settings", TestNotificationModel.open(en));
        assertTrue(TestNotifications.describe(en, TestNotifications.Outcome.BLOCKED_APP).contains("disabled"));
        AppLanguage.set(base, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(base);
        assertEquals("Notificação de teste", TestNotificationModel.title(pt));
        assertEquals("Abrir ajustes", TestNotificationModel.open(pt));
        assertTrue(TestNotifications.describe(pt, TestNotifications.Outcome.BLOCKED_APP).contains("desativadas"));
    }
}

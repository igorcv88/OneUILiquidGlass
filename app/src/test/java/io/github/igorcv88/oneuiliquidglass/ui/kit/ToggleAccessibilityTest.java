package io.github.igorcv88.oneuiliquidglass.ui.kit;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToggleAccessibilityTest {
    @Test public void checkedStateFollowsAppLanguage() {
        Context base = RuntimeEnvironment.getApplication();
        AppLanguage.set(base, AppLanguage.ENGLISH);
        Context en = AppLanguage.wrap(base);
        Toggle toggle = new Toggle(en, new Theme(en, false, true));
        toggle.setContentDescription("Glass on Lock screen");
        AccessibilityNodeInfo off = toggle.createAccessibilityNodeInfo();
        assertTrue(off.isCheckable());
        assertFalse(off.isChecked());
        assertEquals("Off", off.getStateDescription());
        assertEquals("Glass on Lock screen", off.getContentDescription());
        toggle.set(true, false);
        assertEquals("On", toggle.createAccessibilityNodeInfo().getStateDescription());

        AppLanguage.set(base, AppLanguage.PORTUGUESE);
        Context pt = AppLanguage.wrap(base);
        Toggle portugues = new Toggle(pt, new Theme(pt, false, true));
        assertEquals("Desligado", portugues.createAccessibilityNodeInfo().getStateDescription());
        portugues.set(true, false);
        assertEquals("Ligado", portugues.createAccessibilityNodeInfo().getStateDescription());
    }
}

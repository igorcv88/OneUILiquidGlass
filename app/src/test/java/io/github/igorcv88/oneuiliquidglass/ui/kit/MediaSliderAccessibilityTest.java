package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.Scale;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Slider;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MediaSliderAccessibilityTest {
    @Test public void stateAndUserActionUseTheActualValue() {
        Context context = RuntimeEnvironment.getApplication();
        Controls.Control control = Controls.get("mediaart");
        for (String raw : new String[] {"0", "0.25", "0.45", "0.50", "0.75", "1.00"}) {
            Scale scale = Scale.of(control, raw);
            Slider slider = new Slider(context, new Theme(context, false, true));
            slider.configure(scale.steps, scale.index("0.5"), false);
            slider.set(scale.index(raw));
            slider.describe("Media artwork opacity", i -> ControlValues.display(context, control, scale.value(i)));
            assertEquals(ControlValues.display(context, control, raw), slider.createAccessibilityNodeInfo().getStateDescription());
            final String[] changed = {null};
            slider.listener(new Slider.Listener() {
                public void start() { }
                public void change(int index, boolean fromUser) { changed[0] = scale.value(index); }
                public void stop(int index) { }
            });
            assertNull(changed[0]);
            if (Double.parseDouble(raw) < 1) {
                assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
                assertTrue(Double.parseDouble(changed[0]) > Double.parseDouble(raw));
                assertEquals(ControlValues.display(context, control, changed[0]), slider.createAccessibilityNodeInfo().getStateDescription());
            }
        }
    }
}


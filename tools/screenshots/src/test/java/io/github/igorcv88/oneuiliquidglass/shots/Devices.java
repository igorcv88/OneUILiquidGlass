package io.github.igorcv88.oneuiliquidglass.shots;

import app.cash.paparazzi.DeviceConfig;
import java.lang.reflect.Method;

/** Variants of a Galaxy S25 Ultra-like screen (1440 x 3120, 19.5:9): large font, landscape. */
final class Devices {
    static final DeviceConfig BASE = DeviceConfig.PIXEL_6_PRO;

    private static DeviceConfig copy(java.util.function.Consumer<Object[]> edit) {
        try {
            Object[] args = new Object[19];
            String[] getters = {"getScreenHeight", "getScreenWidth", "getXdpi", "getYdpi", "getOrientation", "getUiMode",
                    "getNightMode", "getDensity", "getFontScale", "getLayoutDirection", "getLocale", "getRatio", "getSize",
                    "getKeyboard", "getTouchScreen", "getKeyboardState", "getSoftButtons", "getNavigation", "getScreenRound"};
            for (int i = 0; i < 19; i++) args[i] = DeviceConfig.class.getMethod(getters[i]).invoke(BASE);
            edit.accept(args);
            for (Method m : DeviceConfig.class.getMethods())
                if (m.getName().equals("copy") && m.getParameterCount() == 19) return (DeviceConfig) m.invoke(BASE, args);
            throw new IllegalStateException("copy");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static DeviceConfig largeFont() {
        return copy(a -> a[8] = 1.3f);
    }

    static DeviceConfig landscape() {
        return copy(a -> {
            Object h = a[0];
            a[0] = a[1];
            a[1] = h;
            a[4] = com.android.resources.ScreenOrientation.LANDSCAPE;
        });
    }
}

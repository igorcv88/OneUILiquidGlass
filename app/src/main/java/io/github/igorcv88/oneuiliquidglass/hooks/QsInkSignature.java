package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;

/** Only the DZIF QSIcon ABI; unknown overloads stay native. No default-mask argument. */
final class QsInkSignature {
    static boolean accepts(Method m) {
        Class<?>[] t=m.getParameterTypes();
        return m.getName().equals("QSIcon-fWhpE4E") && m.getReturnType()==void.class && t.length==8
                && t[0].getName().equals("androidx.compose.ui.Modifier")
                && t[1].getName().equals("androidx.compose.ui.graphics.painter.Painter")
                && t[2].getName().equals("java.lang.String") && t[3]==long.class
                && t[4]==float.class && t[5]==boolean.class
                && t[6].getName().equals("androidx.compose.runtime.Composer") && t[7]==int.class;
    }
    private QsInkSignature() {}
}

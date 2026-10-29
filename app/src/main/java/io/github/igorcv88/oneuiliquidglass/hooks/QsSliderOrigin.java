package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.Locale;

/** Identifies a slider by Samsung's caller frames, without reading UI labels or user text. */
final class QsSliderOrigin {
    static final String UNKNOWN = "unknown", BRIGHTNESS = "brightness", VOLUME = "volume";

    private QsSliderOrigin() {}

    static StackTraceElement[] currentStack() {
        try { return Thread.currentThread().getStackTrace(); }
        catch (RuntimeException | LinkageError ignored) { return new StackTraceElement[0]; }
    }

    static String identify(StackTraceElement[] stack) {
        boolean brightness = false, volume = false;
        if (stack == null) return UNKNOWN;
        for (StackTraceElement frame : stack) {
            String name = (frame.getClassName() + "." + frame.getMethodName()).toLowerCase(Locale.ROOT);
            if (name.contains("brightness")) brightness = true;
            if (name.contains("volume")) volume = true;
        }
        if (brightness == volume) return UNKNOWN;
        return brightness ? BRIGHTNESS : VOLUME;
    }

    static boolean suppress(String role) { return !VOLUME.equals(role); }

    static String stackHint(StackTraceElement[] stack) {
        if (stack == null) return "none";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (name.startsWith("java.") || name.startsWith("androidx.compose.")
                    || name.startsWith("kotlin.") || name.startsWith("de.robv.android.xposed.")
                    || name.contains("QsSliderHooks") || name.contains("QsSliderOrigin")) continue;
            if (count++ == 8) break;
            if (out.length() > 0) out.append('|');
            out.append(name).append('#').append(frame.getMethodName());
        }
        return out.toString();
    }
}

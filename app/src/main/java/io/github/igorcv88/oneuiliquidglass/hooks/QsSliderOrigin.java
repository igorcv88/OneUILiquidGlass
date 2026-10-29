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

    /**
     * DZIF inlines the owner out of the draw stack, but its BidirectionalBar composable still
     * receives a callback built by BrightBarKt or VolumeBarKt (argument 12 in the device log).
     * Inspect only the callback's class, never its captured values or accessibility labels.
     */
    static String identifyArguments(Object[] arguments) {
        if (arguments == null) return UNKNOWN;
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (Object argument : arguments) if (argument != null) names.add(argument.getClass().getName());
        return identifyClassNames(names);
    }

    static String identifyClassNames(Iterable<String> names) {
        boolean brightness = false, volume = false;
        for (String name : names) {
            if (name == null) continue;
            if (name.startsWith("com.android.systemui.samsung.quicksetting.ui.tiles.BrightBarKt$")) brightness = true;
            if (name.startsWith("com.android.systemui.samsung.quicksetting.ui.tiles.VolumeBarKt$")) volume = true;
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

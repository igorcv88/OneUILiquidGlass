package io.github.igorcv88.oneuiliquidglass.config;

import java.util.function.Function;

/**
 * The rendering branch SystemUI takes, derived exactly as {@code glass.Tuning} derives it:
 * {@code live = !sfrefract && liveblur && backdrop == auto}. Pure Java so UI, preview and tests
 * share one derivation.
 */
public enum Mode {
    /** LSPosed-only material drawn by the module (default, no root). */
    STANDARD("Vidro padrão"),
    /** sfrefract=1: the compositor library refracts tagged cards. */
    DYNAMIC_LENS("Lente dinâmica"),
    /** Captured or synthetic backdrop (backdrop hybrid/capture/grid, or liveblur=0). */
    CAPTURE("Captura experimental"),
    /** backdrop=off with liveblur=0: Samsung blur only, no sampling. */
    PLAIN_BLUR("Desfoque simples");

    public final String label;

    Mode(String label) {
        this.label = label;
    }

    /** {@code value} returns the value SystemUI would read for a registry key (never null). */
    public static Mode of(Function<String, String> value) {
        boolean sfRefract = number(value.apply("sfrefract")) >= 1;
        if (sfRefract) return DYNAMIC_LENS;
        boolean liveBlur = number(value.apply("liveblur")) >= 1;
        String backdrop = backdrop(value.apply("backdrop"));
        if (liveBlur && backdrop.equals("auto")) return STANDARD;
        return backdrop.equals("off") ? PLAIN_BLUR : CAPTURE;
    }

    /** Tuning accepts only these; anything else reads as auto. */
    static String backdrop(String raw) {
        String b = raw == null ? "" : raw.trim();
        return b.equals("grid") || b.equals("off") || b.equals("capture") || b.equals("hybrid")
                ? b
                : "auto";
    }

    static double number(String raw) {
        try {
            double n = Double.parseDouble(raw == null ? "" : raw.trim());
            return Double.isFinite(n) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

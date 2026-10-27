package io.github.igorcv88.oneuiliquidglass.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Human value text in pt-BR (decimal comma, real units, only the precision that matters) and the
 * inverse for the numeric editor. Raw storage values never change format.
 */
public final class ValueFormat {
    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    private ValueFormat() {}

    private static String num(double n, int decimals) {
        DecimalFormat f = new DecimalFormat(decimals == 0 ? "0" : "0." + "0".repeat(decimals),
                DecimalFormatSymbols.getInstance(BR));
        f.setRoundingMode(RoundingMode.HALF_UP);
        String s = f.format(n);
        return s.equals("-0") ? "0" : s;
    }

    /** Whether the raw value is the "automatic / follows" sentinel. */
    public static boolean isAuto(Controls.Control c, String raw) {
        if (c.autoLabel == null) return false;
        try {
            return Double.parseDouble(raw) < 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static String display(Controls.Control c, String raw) {
        if (raw == null) return "";
        if (isAuto(c, raw)) return c.autoLabel;
        switch (c.format) {
            case TOGGLE:
                return "1".equals(raw) ? "Ligado" : "Desligado";
            case CHOICE:
                String[] choice = c.choices.get(raw);
                return choice != null ? choice[0] : raw;
            case COLOR:
                return "#" + raw.toUpperCase(Locale.ROOT);
            case READONLY:
                return raw.isEmpty() ? "Não informado" : raw;
            default:
                break;
        }
        double n;
        try {
            n = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
        switch (c.format) {
            case PX:
                return num(n, 0) + " px";
            case MS:
                return num(n, 0) + " ms";
            case HZ:
                return num(n, 0) + " Hz";
            case DP:
                return num(n, n == Math.rint(n) ? 0 : 1) + " dp";
            case COUNT:
                return num(n, 0);
            case PERCENT_255:
                return num(n / 255.0 * 100, 0) + " %";
            case PERCENT_1:
                return num(n * 100, 0) + " %";
            case GAIN:
                return "×" + num(n, 2);
            case DEGREES:
                return num(Math.toDegrees(n), 0) + "°";
            default:
                return num(n, 2);
        }
    }

    /** Short unit hint for the numeric editor, in the unit the person types. */
    public static String editUnit(Controls.Control c) {
        switch (c.format) {
            case PX:
                return "px";
            case MS:
                return "ms";
            case HZ:
                return "Hz";
            case DP:
                return "dp";
            case PERCENT_1:
            case PERCENT_255:
                return "%";
            case DEGREES:
                return "°";
            case GAIN:
                return "×";
            default:
                return "";
        }
    }

    /** Raw value -> the number typed in the editor. */
    public static String toEdit(Controls.Control c, String raw) {
        double n;
        try {
            n = Double.parseDouble(raw);
        } catch (RuntimeException e) {
            return raw;
        }
        switch (c.format) {
            case PERCENT_1:
                return num(n * 100, 0);
            case PERCENT_255:
                return num(n / 255.0 * 100, 0);
            case DEGREES:
                return num(Math.toDegrees(n), 0);
            case GAIN:
            case PLAIN:
                return num(n, 2);
            default:
                return num(n, c.spec() != null && c.spec().type == SettingSpec.Type.INT ? 0 : 1);
        }
    }

    /**
     * Editor text -> validated raw value on the control's step. Throws IllegalArgumentException
     * with a short message for anything out of range.
     */
    public static String fromEdit(Controls.Control c, String text) {
        SettingSpec s = c.spec();
        String t = text == null ? "" : text.trim().replace(',', '.').replace("%", "")
                .replace("°", "").replace("×", "").trim();
        double n;
        try {
            n = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Digite um número");
        }
        if (!Double.isFinite(n)) throw new IllegalArgumentException("Digite um número");
        switch (c.format) {
            case PERCENT_1:
                n = n / 100;
                break;
            case PERCENT_255:
                n = Math.round(n / 100 * 255);
                break;
            case DEGREES:
                n = Math.toRadians(n);
                break;
            default:
                break;
        }
        if (s.step > 0 && s.type != SettingSpec.Type.INT)
            n = BigDecimal.valueOf(n).divide(BigDecimal.valueOf(s.step), 0, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(s.step)).doubleValue();
        if (s.type == SettingSpec.Type.INT) n = Math.rint(n);
        if (n < s.min || n > s.max)
            throw new IllegalArgumentException("Use de " + display(c, plain(s.min)) + " a "
                    + display(c, plain(s.max)));
        try {
            return s.validate(plain(n));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Valor fora do intervalo permitido");
        }
    }

    public static String plain(double n) {
        return BigDecimal.valueOf(n).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }
}

package io.github.igorcv88.oneuiliquidglass.config;

import java.util.Locale;

/** Persisted colours use CSS-style RRGGBB or RRGGBBAA; rendering uses Android ARGB. */
public final class Rgba {
    private Rgba() {}

    public static int argb(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (!hex.matches("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
            throw new IllegalArgumentException("Cor esperada: RRGGBB ou RRGGBBAA");
        long n = Long.parseLong(hex, 16);
        return hex.length() == 6 ? (int) (0xff000000L | n)
                : ((int) (n & 0xff) << 24) | (int) ((n >>> 8) & 0xffffff);
    }

    public static String hex(int argb) {
        return String.format(Locale.ROOT, "%06x%02x", argb & 0xffffff, argb >>> 24);
    }

    public static int rgb(String raw) { return argb(raw) & 0xffffff; }
}

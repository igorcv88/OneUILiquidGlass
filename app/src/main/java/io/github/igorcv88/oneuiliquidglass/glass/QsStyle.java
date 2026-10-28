package io.github.igorcv88.oneuiliquidglass.glass;

import java.util.Objects;
import java.util.function.Function;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;

/**
 * Immutable provider snapshot. Read on configuration events, never from a draw callback.
 *
 * <p>qs.lightfill / qs.lightcolor are the faint dark tint over light backdrops (capped at 18 %).
 * Retired keys are read and discarded, never applied: qs.darkfill, qs.darkcolor and qs.idle (the
 * white films over dark backdrops and inactive discs), qs.active (discs keep Samsung's colour),
 * qs.track (the third slider surface) and qs.lightstyle. They stay in the catalogue only so that
 * older profiles import and export unchanged; no stored value can bring a film back.</p>
 */
public final class QsStyle {
    public final float lightFill, darkRim, lightRim, outer, fill;
    public final int lightColor, edgeBlur;
    private final String fingerprint;
    private final Function<String, String> values;

    public static QsStyle read() { return new QsStyle(ConfigClient::override); }
    public QsStyle(Function<String, String> values) {
        this.values = values;
        lightFill = Math.min(QuickSettingsGlass.MAX_LIGHT_FILL, number("qs.lightfill", QuickSettingsGlass.LIGHT_FILL));
        lightColor = color("qs.lightcolor", QuickSettingsGlass.LIGHT_TINT);
        darkRim = number("qs.darkrim", .26f); lightRim = number("qs.lightrim", .18f);
        edgeBlur = Math.round(number("qs.edgeblur", 8));
        outer = number("qs.outer", 1);
        fill = number("qs.fill", QuickSettingsGlass.SLIDER_FILL);
        // Registered for profile round-trips only: read and discarded, so no stored value can draw.
        prop("qs.darkfill"); prop("qs.darkcolor"); prop("qs.idle"); prop("qs.active");
        prop("qs.track"); prop("qs.lightstyle");
        fingerprint = lightFill+":"+lightColor+":"+darkRim+":"+lightRim+":"+edgeBlur+":"+outer+":"+fill;
    }
    private String prop(String key) {
        String s = values.apply(key);
        SettingSpec spec = SettingsRegistry.get(key);
        return s != null ? s : spec == null ? "" : spec.defaultValue;
    }
    private float number(String key, float fallback) {
        try {
            SettingSpec spec = SettingsRegistry.get(key);
            return Float.parseFloat(spec.validate(prop(key)));
        } catch (IllegalArgumentException | NullPointerException e) { return fallback; }
    }
    private int color(String key, int fallback) {
        try { return Integer.parseInt(SettingsRegistry.get(key).validate(prop(key)), 16); }
        catch (IllegalArgumentException | NullPointerException e) { return fallback; }
    }
    @Override public boolean equals(Object o) { return o instanceof QsStyle && fingerprint.equals(((QsStyle)o).fingerprint); }
    @Override public int hashCode() { return Objects.hash(fingerprint); }
}

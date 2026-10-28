package io.github.igorcv88.oneuiliquidglass.glass;

import java.util.Objects;
import java.util.function.Function;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;

/** Immutable provider snapshot. Read on configuration events, never from a draw callback. */
public final class QsStyle {
    public final float darkFill, lightFill, idle, active, darkRim, lightRim, outer, track, fill;
    public final int darkColor, lightColor, edgeBlur;
    public final boolean lightGlass;
    private final String fingerprint;
    private final Function<String, String> values;

    public static QsStyle read() { return new QsStyle(ConfigClient::override); }
    public QsStyle(Function<String, String> values) {
        this.values = values;
        darkFill = number("qs.darkfill", .10f); lightFill = number("qs.lightfill", .66f);
        idle = number("qs.idle", .08f); active = number("qs.active", .90f);
        darkRim = number("qs.darkrim", .26f); lightRim = number("qs.lightrim", .18f);
        edgeBlur = Math.round(number("qs.edgeblur", 8));
        outer = number("qs.outer", 1); track = number("qs.track", .88f); fill = number("qs.fill", .88f);
        darkColor = color("qs.darkcolor", 0xffffff); lightColor = color("qs.lightcolor", 0x141823);
        lightGlass = "light".equals(prop("qs.lightstyle"));
        fingerprint = darkFill+":"+lightFill+":"+idle+":"+active+":"+darkRim+":"+lightRim+":"
                +edgeBlur+":"+outer+":"+track+":"+fill+":"+darkColor+":"+lightColor+":"+lightGlass;
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

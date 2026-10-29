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
 * white films over dark backdrops and an old global inactive-disc alpha), qs.active (replaced by scene-aware tile palette),
 * qs.track (the third slider surface) and qs.lightstyle. They stay in the catalogue only so that
 * older profiles import and export unchanged; no stored value can bring a film back.</p>
 */
public final class QsStyle {
    public final float lightFill, darkRim, lightRim, outer, fill;
    public final int lightColor, edgeBlur;
    public final boolean tileLight, tileDark, hideOffDiscs;
    public final int tileLightOn, tileLightOff, tileLightOnInk, tileLightOffInk;
    public final int tileDarkOn, tileDarkOff, tileDarkOnInk, tileDarkOffInk;
    public final float tileLightOffAlpha, tileDarkOffAlpha;
    private final String fingerprint;
    private final Function<String, String> values;

    public static QsStyle read() { return new QsStyle(ConfigClient::override); }
    public QsStyle(Function<String, String> values) {
        this.values = values;
        lightFill = Math.min(QuickSettingsGlass.MAX_LIGHT_FILL, number("qs.lightfill", QuickSettingsGlass.LIGHT_FILL));
        // Only a dark tint: an old light or white qs.lightcolor falls back to the default.
        lightColor = QuickSettingsGlass.darkTint(color("qs.lightcolor", QuickSettingsGlass.LIGHT_TINT));
        darkRim = number("qs.darkrim", .26f); lightRim = number("qs.lightrim", .18f);
        edgeBlur = Math.round(number("qs.edgeblur", 8));
        outer = number("qs.outer", 1);
        fill = number("qs.fill", QuickSettingsGlass.SLIDER_FILL);
        tileLight = number("qs.tilelight", 1) >= .5f;
        tileDark = number("qs.tiledark", 0) >= .5f;
        hideOffDiscs = number("qs.hideoffdiscs", 1) >= .5f;
        tileLightOn = color("qs.tilelighton", 0xcdd7e5);
        tileLightOff = color("qs.tilelightoff", 0x202c3c);
        tileLightOnInk = color("qs.tilelightonink", 0x20242e);
        tileLightOffInk = color("qs.tilelightoffink", 0x343a46);
        tileLightOffAlpha = number("qs.tilelightoffalpha", .24f);
        tileDarkOn = color("qs.tiledarkon", 0xffffff);
        tileDarkOff = color("qs.tiledarkoff", 0xffffff);
        tileDarkOnInk = color("qs.tiledarkonink", 0x1d1d1d);
        tileDarkOffInk = color("qs.tiledarkoffink", 0xffffff);
        tileDarkOffAlpha = number("qs.tiledarkoffalpha", .10f);
        // Registered for profile round-trips only: read and discarded, so no stored value can draw.
        prop("qs.darkfill"); prop("qs.darkcolor"); prop("qs.idle"); prop("qs.active");
        prop("qs.track"); prop("qs.lightstyle");
        fingerprint = lightFill+":"+lightColor+":"+darkRim+":"+lightRim+":"+edgeBlur+":"+outer+":"+fill
                +":"+tileLight+":"+tileDark+":"+hideOffDiscs+":"+tileLightOn+":"+tileLightOff+":"+tileLightOnInk+":"+tileLightOffInk
                +":"+tileLightOffAlpha+":"+tileDarkOn+":"+tileDarkOff+":"+tileDarkOnInk+":"+tileDarkOffInk+":"+tileDarkOffAlpha;
    }
    /** No change for unknown backgrounds, disc-less tiles and unselected palette modes. */
    public int tileBackground(QuickSettingsGlass.Kind kind, QsScene.Tone tone, int nativeColor) {
        if (kind != QuickSettingsGlass.Kind.ACTIVE && kind != QuickSettingsGlass.Kind.IDLE) return nativeColor;
        if (tone == QsScene.Tone.UNKNOWN) return nativeColor;
        // The user-facing default is a genuinely disc-less inactive tile. Do not infer a circle
        // from a generic Compose shape: this is called only after QuickTile ownership is bound.
        // With Theme Park disabled an opaque native OFF disc can be misclassified ACTIVE:
        // only a verified IDLE model reaches this branch. Never guess from circle shape.
        if (kind == QuickSettingsGlass.Kind.IDLE && hideOffDiscs) return 0;
        if (tone == QsScene.Tone.LIGHT_SCENE && tileLight)
            return kind == QuickSettingsGlass.Kind.ACTIVE ? 0xff000000 | tileLightOn
                    : QuickSettingsGlass.withAlpha(tileLightOff, tileLightOffAlpha);
        if (tone == QsScene.Tone.DARK_SCENE && tileDark)
            return kind == QuickSettingsGlass.Kind.ACTIVE ? 0xff000000 | tileDarkOn
                    : QuickSettingsGlass.withAlpha(tileDarkOff, tileDarkOffAlpha);
        return nativeColor;
    }
    /** Tint only neutral monochrome icon ink; coloured icons keep their native tint. */
    public int tileInk(QuickSettingsGlass.Kind kind, QsScene.Tone tone, int nativeColor) {
        if (!QuickSettingsGlass.neutral(nativeColor)) return nativeColor;
        int tint;
        if (tone == QsScene.Tone.LIGHT_SCENE && tileLight)
            tint = kind == QuickSettingsGlass.Kind.ACTIVE ? tileLightOnInk
                    : kind == QuickSettingsGlass.Kind.IDLE ? tileLightOffInk : -1;
        else if (tone == QsScene.Tone.DARK_SCENE && tileDark)
            tint = kind == QuickSettingsGlass.Kind.ACTIVE ? tileDarkOnInk
                    : kind == QuickSettingsGlass.Kind.IDLE ? tileDarkOffInk : -1;
        else tint = -1;
        return tint == -1 ? nativeColor : QuickSettingsGlass.withAlpha(tint, QuickSettingsGlass.alpha(nativeColor));
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

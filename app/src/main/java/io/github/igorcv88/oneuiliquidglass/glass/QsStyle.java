package io.github.igorcv88.oneuiliquidglass.glass;

import java.util.Objects;
import java.util.function.Function;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.SettingSpec;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.Rgba;

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
    public final float lightFill, lightGlassOpacity, lightOptics, darkRim, lightRim, outer, fill;
    public final int lightColor, lightGlassColor, edgeBlur;
    public final boolean tileLight, tileDark, hideOffDiscs;
    public final int tileLightOn, tileLightOff, tileLightOnInk, tileLightOffInk;
    public final int tileDarkOn, tileDarkOff, tileDarkOnInk, tileDarkOffInk;
    public final int sliderLightColor, sliderDarkColor;
    public final float tileLightOffAlpha, tileDarkOffAlpha;
    private final String fingerprint;
    private final Function<String, String> values;

    public static QsStyle read() { return new QsStyle(ConfigClient::override); }
    public QsStyle(Function<String, String> values) {
        this.values = values;
        lightFill = Math.min(QuickSettingsGlass.MAX_LIGHT_FILL, number("qs.lightfill", QuickSettingsGlass.LIGHT_FILL))
                * QuickSettingsGlass.alpha(color("qs.lightcolor", 0xff141823));
        // Only a dark tint: an old light or white qs.lightcolor falls back to the default.
        lightColor = QuickSettingsGlass.darkTint(color("qs.lightcolor", 0xff000000 | QuickSettingsGlass.LIGHT_TINT));
        lightGlassOpacity = number("qs.lightglassopacity", 0f);
        lightGlassColor = color("qs.lightglasscolor", 0xffffffff);
        lightOptics = number("qs.lightoptics", 1f);
        darkRim = number("qs.darkrim", .26f); lightRim = number("qs.lightrim", .18f);
        edgeBlur = Math.round(number("qs.edgeblur", 8));
        outer = number("qs.outer", 1);
        fill = number("qs.fill", QuickSettingsGlass.SLIDER_FILL);
        tileLight = number("qs.tilelight", 1) >= .5f;
        tileDark = number("qs.tiledark", 0) >= .5f;
        hideOffDiscs = number("qs.hideoffdiscs", 1) >= .5f;
        tileLightOn = color("qs.tilelighton", 0xffcdd7e5);
        tileLightOff = color("qs.tilelightoff", 0xff202c3c);
        tileLightOnInk = color("qs.tilelightonink", 0xff20242e);
        tileLightOffInk = color("qs.tilelightoffink", 0xff343a46);
        tileLightOffAlpha = number("qs.tilelightoffalpha", .24f);
        tileDarkOn = color("qs.tiledarkon", 0xffffffff);
        tileDarkOff = color("qs.tiledarkoff", 0xffffffff);
        tileDarkOnInk = color("qs.tiledarkonink", 0xff1d1d1d);
        tileDarkOffInk = color("qs.tiledarkoffink", 0xffffffff);
        sliderLightColor = color("qs.sliderlightcolor", 0xffffffff);
        sliderDarkColor = color("qs.sliderdarkcolor", 0xffffffff);
        tileDarkOffAlpha = number("qs.tiledarkoffalpha", .10f);
        // Registered for profile round-trips only: read and discarded, so no stored value can draw.
        prop("qs.darkfill"); prop("qs.darkcolor"); prop("qs.idle"); prop("qs.active");
        prop("qs.track"); prop("qs.lightstyle");
        fingerprint = lightFill+":"+lightColor+":"+lightGlassOpacity+":"+lightGlassColor+":"+lightOptics
                +":"+darkRim+":"+lightRim+":"+edgeBlur+":"+outer+":"+fill
                +":"+tileLight+":"+tileDark+":"+hideOffDiscs+":"+tileLightOn+":"+tileLightOff+":"+tileLightOnInk+":"+tileLightOffInk
                +":"+tileLightOffAlpha+":"+tileDarkOn+":"+tileDarkOff+":"+tileDarkOnInk+":"+tileDarkOffInk+":"+tileDarkOffAlpha
                +":"+sliderLightColor+":"+sliderDarkColor;
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
            return kind == QuickSettingsGlass.Kind.ACTIVE ? tileLightOn
                    : QuickSettingsGlass.withAlpha(tileLightOff, QuickSettingsGlass.alpha(tileLightOff)*tileLightOffAlpha);
        if (tone == QsScene.Tone.DARK_SCENE && tileDark)
            return kind == QuickSettingsGlass.Kind.ACTIVE ? tileDarkOn
                    : QuickSettingsGlass.withAlpha(tileDarkOff, QuickSettingsGlass.alpha(tileDarkOff)*tileDarkOffAlpha);
        return nativeColor;
    }
    /** Tint only neutral monochrome ink; verified disc-less controls use off-ink on light glass. */
    public int tileInk(QuickSettingsGlass.Kind kind, QsScene.Tone tone, int nativeColor) {
        if (!QuickSettingsGlass.neutral(nativeColor)) return nativeColor;
        int tint;
        if (kind != QuickSettingsGlass.Kind.ACTIVE && kind != QuickSettingsGlass.Kind.IDLE
                && kind != QuickSettingsGlass.Kind.NONE) return nativeColor;
        if (tone == QsScene.Tone.LIGHT_SCENE && tileLight)
            tint = kind == QuickSettingsGlass.Kind.ACTIVE ? tileLightOnInk : tileLightOffInk;
        else if (tone == QsScene.Tone.DARK_SCENE && tileDark)
            tint = kind == QuickSettingsGlass.Kind.ACTIVE ? tileDarkOnInk : tileDarkOffInk;
        else return nativeColor;
        // White ARGB (0xffffffff) is -1 as a signed int, so it cannot be a sentinel.
        return QuickSettingsGlass.withAlpha(tint,
                QuickSettingsGlass.alpha(tint) * QuickSettingsGlass.alpha(nativeColor));
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
        try { return Rgba.argb(SettingsRegistry.get(key).validate(prop(key))); }
        catch (IllegalArgumentException | NullPointerException e) { return fallback; }
    }
    @Override public boolean equals(Object o) { return o instanceof QsStyle && fingerprint.equals(((QsStyle)o).fingerprint); }
    @Override public int hashCode() { return Objects.hash(fingerprint); }
}

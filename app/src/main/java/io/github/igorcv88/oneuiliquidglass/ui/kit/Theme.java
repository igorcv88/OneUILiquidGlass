package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * Design tokens. Night blue / neutral surfaces with the icon's cyan and lilac as accents; glass is
 * reserved for navigation and the sample. All sizes are dp, text sp (scales with font size).
 */
public final class Theme {
    public final boolean dark;
    public final float density;
    /** Animations shortened to zero when the person or the system asks for less motion. */
    public final boolean reduceMotion;

    public final int bg, surface, surfaceHigh, surfaceLow, stroke, divider;
    public final int ink, inkMuted, inkFaint, inkDisabled;
    public final int accent, onAccent, accentContainer, onAccentContainer, accentSoft;
    public final int lilac;
    public final int warn, warnContainer, onWarnContainer;
    public final int error, errorContainer, onErrorContainer;
    public final int ok;
    public final int scrim, ripple, track, thumbOff;

    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL),
            MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public Theme(Context c, boolean dark, boolean reduceMotion) {
        this.dark = dark;
        this.density = c.getResources().getDisplayMetrics().density;
        this.reduceMotion = reduceMotion || !ValueAnimator.areAnimatorsEnabled();
        if (dark) {
            bg = 0xff0d1219;
            surface = 0xff161e2a;
            surfaceHigh = 0xff1c2534;
            surfaceLow = 0xff111823;
            stroke = 0x1fffffff;
            divider = 0x14ffffff;
            ink = 0xffedf2f8;
            inkMuted = 0xffa6b3c4;
            inkFaint = 0xff76839a;
            inkDisabled = 0x61edf2f8;
            accent = 0xff80dfe9;
            onAccent = 0xff05292f;
            accentContainer = 0xff1b3a44;
            onAccentContainer = 0xffc2f3f8;
            accentSoft = 0x2980dfe9;
            lilac = 0xffbcaaf4;
            warn = 0xfff2bd72;
            warnContainer = 0xff35291a;
            onWarnContainer = 0xffffdfb3;
            error = 0xfff2928a;
            errorContainer = 0xff3a1f1e;
            onErrorContainer = 0xffffd9d5;
            ok = 0xff86d9a9;
            scrim = 0x99000000;
            ripple = 0x2effffff;
            track = 0xff2d3a4d;
            thumbOff = 0xffb3bfcf;
        } else {
            bg = 0xfff2f5f9;
            surface = 0xffffffff;
            surfaceHigh = 0xffffffff;
            surfaceLow = 0xffe8edf3;
            stroke = 0x14102030;
            divider = 0x10102030;
            ink = 0xff121a26;
            inkMuted = 0xff4e5d71;
            inkFaint = 0xff7d8b9f;
            inkDisabled = 0x61121a26;
            accent = 0xff11788a;
            onAccent = 0xffffffff;
            accentContainer = 0xffd3f0f3;
            onAccentContainer = 0xff053940;
            accentSoft = 0x2211788a;
            lilac = 0xff6b56c2;
            warn = 0xff8f5600;
            warnContainer = 0xfffff0d9;
            onWarnContainer = 0xff4d2e00;
            error = 0xffb3261e;
            errorContainer = 0xfffde3e0;
            onErrorContainer = 0xff5c1410;
            ok = 0xff1d7a4b;
            scrim = 0x66000000;
            ripple = 0x1f102030;
            track = 0xffd5dde7;
            thumbOff = 0xffffffff;
        }
    }

    public static boolean systemDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public int dp(float v) {
        return Math.round(v * density);
    }

    public float dpf(float v) {
        return v * density;
    }

    public long ms(long duration) {
        return reduceMotion ? 0 : duration;
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    // Type scale (sp).
    public static final float DISPLAY = 26, TITLE = 20, HEADLINE = 17, BODY = 16, LABEL = 14,
            CAPTION = 13, SMALL = 12;
    // Shape and rhythm (dp).
    public static final int GUTTER = 20, CARD_RADIUS = 24, ROW_MIN = 56, BUTTON = 52,
            BUTTON_SMALL = 48, ICON = 24, TOUCH = 48;
    public static final long FAST = 140, STANDARD_MS = 220, EMPHASIZED = 320;
}

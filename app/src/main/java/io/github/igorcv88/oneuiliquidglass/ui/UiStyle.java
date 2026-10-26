package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.*;

/** Explicit styling: never inherit the OEM's rectangular default Button background. */
public final class UiStyle {
    private UiStyle() {}

    public static int dp(Context c, float value) {
        return (int) (c.getResources().getDisplayMetrics().density * value + .5f);
    }

    public static GradientDrawable pane(Context c, boolean light, boolean glass, int radius) {
        GradientDrawable d =
                glass
                        ? new GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                light
                                        ? new int[] {0xe8e2f7fc, 0xdde5e2f7, 0xeef7fbff}
                                        : new int[] {0xe6324b60, 0xe52a344b, 0xf01c293a})
                        : new GradientDrawable();
        if (!glass) d.setColor(light ? 0xfff8faff : 0xff1a2637);
        d.setCornerRadius(dp(c, radius));
        d.setStroke(dp(c, 1), light ? 0x228298b0 : 0x244fa4bc);
        return d;
    }

    public static void button(Button b, boolean light, boolean accent) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        GradientDrawable base = pane(c, light, accent, 18);
        b.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(light ? 0x286c97b4 : 0x356fe1eb), base, null));
        b.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {
                            light ? 0xff929bab : 0xff617086,
                            light ? 0xff173b50 : accent ? 0xffb8f4fa : 0xffdce8f7
                        }));
        b.setElevation(accent ? dp(c, 2) : 0);
    }

    public static void toggle(Switch s, boolean light) {
        s.setThumbTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {
                            light ? 0xffecffff : 0xffc5f6fa, light ? 0xfff4f6fc : 0xffb0bed0
                        }));
        s.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {
                            light ? 0xff328da0 : 0xff368c9e, light ? 0xffbdc8d8 : 0xff435068
                        }));
        s.setShowText(false);
        s.setSwitchPadding(dp(s.getContext(), 16));
    }

    public static void slider(SeekBar s, boolean light) {
        s.setProgressTintList(ColorStateList.valueOf(light ? 0xff297f95 : 0xff70dde8));
        s.setThumbTintList(ColorStateList.valueOf(light ? 0xff297f95 : 0xffb4f2fa));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(light ? 0xffcad5e4 : 0xff3a4c64));
        s.setMinimumHeight(dp(s.getContext(), 48));
    }

    public static void space(View v, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(v.getContext(), 6);
        p.bottomMargin = dp(v.getContext(), bottom);
        v.setLayoutParams(p);
    }
}

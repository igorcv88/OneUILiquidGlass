package io.github.igorcv88.oneuiliquidglass.glass;

/** Contrast on the effective surface, independent of a control's ON/OFF state. */
public final class QsSurfaceInk {
    private QsSurfaceInk() {}
    public static int onSurface(int nativeInk,int surface,boolean lightScene) {
        if(!QuickSettingsGlass.neutral(nativeInk))return nativeInk;
        int opaque=QuickSettingsGlass.over(surface,lightScene?0xffffffff:0xff000000);
        double l=luminance(opaque);
        double dark=luminance(0xff000000|QuickSettingsGlass.DARK_INK);
        boolean darkInk=(l+.05)/(dark+.05)>=1.05/(l+.05);
        return QuickSettingsGlass.ink(nativeInk,darkInk);
    }
    private static double luminance(int c) {
        return .2126*linear((c>>>16)&255)+.7152*linear((c>>>8)&255)+.0722*linear(c&255);
    }
    private static double linear(int v) {double c=v/255.;return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
}

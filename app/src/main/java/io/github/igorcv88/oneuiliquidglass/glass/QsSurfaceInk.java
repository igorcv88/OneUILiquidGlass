package io.github.igorcv88.oneuiliquidglass.glass;

/** Contrast on the effective surface, independent of a control's ON/OFF state. */
public final class QsSurfaceInk {
    private QsSurfaceInk() {}

    /** RoundIcon sometimes receives iconColor.invoke's already-interpolated value. Restore
     * the native input before calculating this frame's tint, so the phase is applied once. */
    public static int originalForRound(int roundInk,int derivedNative,int derivedMapped) {
        return roundInk==derivedMapped?derivedNative:roundInk;
    }
    /**
     * The native RoundIcon path supplies a verified QuickTile state. Use the same configurable
     * palette as the Compose painter path; otherwise the small OFF icons remain opaque native
     * black/white even when the user lowers qs.tilelightoffink/qs.tiledarkoffink alpha.
     *
     * An ACTIVE control with an opaque disc uses the chosen ink; a disc-less or very translucent
     * active control keeps the surface-aware contrast colour, but retains the chosen ink alpha.
     * Disabled/unavailable states keep the old automatic contrast behaviour.
     */
    public static int onVerifiedTile(int nativeInk,int surface,int state,QsScene.Tone scene,QsStyle style) {
        boolean light=scene==QsScene.Tone.LIGHT_SCENE;
        if((state!=1&&state!=2) || style==null || !QuickSettingsGlass.neutral(nativeInk))
            return onSurface(nativeInk,surface,light);
        QuickSettingsGlass.Kind kind=state==2?QuickSettingsGlass.Kind.ACTIVE:QuickSettingsGlass.Kind.IDLE;
        // iconColor.invoke and RoundIcon can both see the same glyph. The second hook may receive
        // the first hook's tinted alpha: multiply-on-every-pass would turn 50% into 25%.
        // Cap at the requested opacity instead, keeping a lower native alpha if present.
        int palette=style.tileInk(kind,scene,0xff000000|(nativeInk&0xffffff));
        int chosen=QuickSettingsGlass.withAlpha(palette,Math.min(
                QuickSettingsGlass.alpha(palette),QuickSettingsGlass.alpha(nativeInk)));
        if(state==1 || QuickSettingsGlass.alpha(surface)>=.65f)return chosen;
        int contrasted=onSurface(nativeInk,surface,light);
        return QuickSettingsGlass.withAlpha(contrasted,QuickSettingsGlass.alpha(chosen));
    }
    /** Both RoundIcon and its painter read the same frame's fractional scene palette. */
    public static int onVerifiedTile(int nativeInk,int surface,int state,QsScene.Tone scene,QsStyle style,Float lightFraction) {
        if(lightFraction==null||scene==QsScene.Tone.UNKNOWN)
            return onVerifiedTile(nativeInk,surface,state,scene,style);
        int dark=style!=null&&style.tileDark
                ?onVerifiedTile(nativeInk,surface,state,QsScene.Tone.DARK_SCENE,style):nativeInk;
        int light=style!=null&&style.tileLight
                ?onVerifiedTile(nativeInk,surface,state,QsScene.Tone.LIGHT_SCENE,style):nativeInk;
        return InkTransition.lerpPremultiplied(dark,light,lightFraction);
    }
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

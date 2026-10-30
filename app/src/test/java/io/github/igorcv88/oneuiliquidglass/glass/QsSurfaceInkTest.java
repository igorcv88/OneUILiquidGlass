package io.github.igorcv88.oneuiliquidglass.glass;
import org.junit.Test;
import static org.junit.Assert.*;
public class QsSurfaceInkTest {
    @Test public void activeDiscAndDisclessGlyphChooseDifferentInkOnWhite(){
        assertEquals(0xffffffff,QsSurfaceInk.onSurface(0xff000000,0xff000000,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0xffffffff,false));
    }
    @Test public void zeroSliderFollowsGlassAndFilledSliderFollowsProgress(){
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0,true));
        assertEquals(0xffffffff,QsSurfaceInk.onSurface(0xff000000,0,false));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xff000000,0xffffffff,false));
    }
    @Test public void alphaAndAccentWarningsArePreserved(){
        assertEquals(0x801d1d1d,QsSurfaceInk.onSurface(0x80ffffff,0,true));
        assertEquals(0xffff2200,QsSurfaceInk.onSurface(0xffff2200,0,true));
        assertEquals(0x00ffffff,QsSurfaceInk.onSurface(0x00ffffff,0,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0x20000000,true));
    }
    @Test public void nativeSmallOffGlyphRespectsConfiguredOpacityInBothScenes(){
        QsStyle style=new QsStyle(k->k.equals("qs.tilelightoffink")?"1d202580":
                k.equals("qs.tiledarkoffink")?"ffffff73":
                k.equals("qs.tiledark")?"1":null);
        assertEquals(0x801d2025,QsSurfaceInk.onVerifiedTile(0xff000000,0,1,
                QsScene.Tone.LIGHT_SCENE,style));
        assertEquals(0x73ffffff,QsSurfaceInk.onVerifiedTile(0xffffffff,0,1,
                QsScene.Tone.DARK_SCENE,style));
        assertEquals(0x801d2025,QsSurfaceInk.onVerifiedTile(0x80000000,0,1,
                QsScene.Tone.LIGHT_SCENE,style));
        assertEquals("RoundIcon must not square the configured alpha",0x801d2025,
                QsSurfaceInk.onVerifiedTile(0x801d2025,0,1,QsScene.Tone.LIGHT_SCENE,style));
        assertEquals(0xff343a46,QsSurfaceInk.onVerifiedTile(0xff000000,0,1,
                QsScene.Tone.LIGHT_SCENE,new QsStyle(k->null)));
    }
    @Test public void nativeActiveDiscUsesConfiguredAlphaButDisclessRemainsLegible(){
        QsStyle style=new QsStyle(k->k.equals("qs.tilelightonink")?"ffffffb0":null);
        assertEquals(0xb0ffffff,QsSurfaceInk.onVerifiedTile(0xff000000,0xe017191c,2,
                QsScene.Tone.LIGHT_SCENE,style));
        assertEquals(0xb01d1d1d,QsSurfaceInk.onVerifiedTile(0xff000000,0,2,
                QsScene.Tone.LIGHT_SCENE,style));
        assertEquals(0xff12a0ef,QsSurfaceInk.onVerifiedTile(0xff12a0ef,0,1,
                QsScene.Tone.LIGHT_SCENE,style));
        assertEquals(0xffffffff,QsSurfaceInk.onVerifiedTile(0xffffffff,0,0,
                QsScene.Tone.DARK_SCENE,style));
    }
    @Test public void crossfadeUsesOneProgressForBothSmallTileEndpoints(){
        QsStyle style=new QsStyle(k->k.equals("qs.tilelightoffink")?"000c2180":
                k.equals("qs.tiledarkoffink")?"ffffffa0":
                k.equals("qs.tiledark")?"1":null);
        int light=QsSurfaceInk.onVerifiedTile(0xff000000,0,1,QsScene.Tone.LIGHT_SCENE,style);
        int dark=QsSurfaceInk.onVerifiedTile(0xff000000,0,1,QsScene.Tone.DARK_SCENE,style);
        assertEquals(dark,QsSurfaceInk.onVerifiedTile(0xff000000,0,1,QsScene.Tone.LIGHT_SCENE,style,0f));
        assertEquals(light,QsSurfaceInk.onVerifiedTile(0xff000000,0,1,QsScene.Tone.LIGHT_SCENE,style,1f));
        assertEquals(InkTransition.lerpPremultiplied(dark,light,.5f),
                QsSurfaceInk.onVerifiedTile(0xff000000,0,1,QsScene.Tone.LIGHT_SCENE,style,.5f));
    }
    @Test public void roundIconUsesOriginalInkInsteadOfInterpolatingDerivedInkAgain(){
        QsStyle style=new QsStyle(k->k.equals("qs.tilelightoffink")?"000000ff":null);
        int nativeInk=0xffffffff;
        int first=QsSurfaceInk.onVerifiedTile(nativeInk,0,1,QsScene.Tone.LIGHT_SCENE,style,.5f);
        int twice=QsSurfaceInk.onVerifiedTile(first,0,1,QsScene.Tone.LIGHT_SCENE,style,.5f);
        assertNotEquals("the old pipeline blended the already tinted glyph twice",first,twice);
        int original=QsSurfaceInk.originalForRound(first,nativeInk,first);
        assertEquals(nativeInk,original);
        assertEquals(first,QsSurfaceInk.onVerifiedTile(original,0,1,QsScene.Tone.LIGHT_SCENE,style,.5f));
        assertEquals(0xff00aa00,QsSurfaceInk.originalForRound(0xff00aa00,nativeInk,first));
    }
}

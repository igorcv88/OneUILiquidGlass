package io.github.igorcv88.oneuiliquidglass.glass;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind.*;

public class QsAdaptiveTest {
    private QsStyle defaults(){return new QsStyle(k->null);}
    @Test public void sceneHasHysteresisCooldownAndRejectsInvalidSamples(){
        QsScene s=new QsScene();assertEquals(UNKNOWN,s.tone());
        assertFalse(s.accept(Float.NaN,0));assertFalse(s.accept(-1,0));
        assertTrue(s.accept(.9f,0));assertEquals(LIGHT_SCENE,s.tone());
        assertFalse(s.accept(.2f,499));assertFalse(s.accept(.7f,500));
        assertTrue(s.accept(.64f,501));assertEquals(DARK_SCENE,s.tone());
        assertFalse(s.accept(.74f,1001));assertTrue(s.accept(.76f,1002));
        s.reset();assertEquals(UNKNOWN,s.tone());
    }
    @Test public void whiteBackdropIsTranslucentInEitherAndroidThemeAndUnknownUsesLightVeil(){
        QsStyle s=defaults();
        int a=QuickSettingsGlass.fill(CONTAINER,0x33000000,true,LIGHT_SCENE,s);
        assertEquals(a,QuickSettingsGlass.fill(CONTAINER,0x33000000,false,LIGHT_SCENE,s));
        assertEquals(0x141823,a&0xffffff);
        for(boolean dark:new boolean[]{true,false})assertEquals(QuickSettingsGlass.fill(CONTAINER,0x33000000,dark),
                QuickSettingsGlass.fill(CONTAINER,0x33000000,dark,UNKNOWN,s));
        assertEquals(QuickSettingsGlass.fill(CONTAINER,0,true),QuickSettingsGlass.fill(CONTAINER,0,true,DARK_SCENE,s));
    }
    @Test public void placeholdersAndActiveStateColoursArePreserved(){
        QsStyle s=defaults();
        assertEquals(0,QuickSettingsGlass.fill(NONE,0,true,LIGHT_SCENE,s));
        assertEquals(0x3e91ff,QuickSettingsGlass.fill(ACTIVE,0xff3e91ff,true,LIGHT_SCENE,s)&0xffffff);
        assertEquals(0x99,QuickSettingsGlass.fill(ACTIVE,0x99ffffff,true,LIGHT_SCENE,s)>>>24);
        assertEquals(0xff12a0ef,QuickSettingsGlass.ink(0xff12a0ef,true));
    }
    @Test public void knownInactiveDarkGlyphChangesInkWithoutInvertingAnActiveDiscGlyph(){
        assertEquals(0xffffffff,QuickSettingsGlass.iconInk(0xff1d1d1d,IDLE,false));
        assertEquals(0xff1d1d1d,QuickSettingsGlass.iconInk(0xff1d1d1d,ACTIVE,false));
        assertEquals(0xff1d1d1d,QuickSettingsGlass.iconInk(0xff1d1d1d,NONE,false));
        assertEquals(0xff12a0ef,QuickSettingsGlass.iconInk(0xff12a0ef,IDLE,false));
    }
    @Test public void foregroundContrastIsCompositedOnRealWhiteBlackAndBlueBackdrops(){
        for(int bg:new int[]{0xffffffff,0xff000000,0xff0000ff}){
            boolean light=bg==0xffffffff;
            int capsule=QuickSettingsGlass.fill(CONTAINER,0,true,light?LIGHT_SCENE:DARK_SCENE,defaults());
            assertTrue((capsule>>>24)<=46);
            int ink=QuickSettingsGlass.ink(0xffffffff,light);
            assertTrue(contrast(over(ink,over(capsule,bg)),over(capsule,bg))>=4.5);
            assertTrue(contrast(ink,over(capsule,bg))>=3);
        }
    }
    @Test public void legacyProfilesCannotRestoreAnOpaqueCapsuleOrVisualTrack(){
        QsStyle a=defaults(),b=new QsStyle(k->k.equals("qs.lightstyle")?"obsidian":k.equals("qs.track")?".1":null);
        assertEquals(a,b);assertTrue(b.lightGlass);
        QsStyle old=new QsStyle(k->k.equals("qs.lightfill")?".66":null);
        assertEquals(.18f,old.lightFill,.0001);
        assertNotEquals(a,new QsStyle(k->k.equals("qs.fill")?"0.25":null));
        assertEquals(a,new QsStyle(k->k.equals("qs.fill")?"NaN":null));
    }
    @Test public void twoSurfaceGeometryAcrossRangeDirectionsAndPermittedThickness(){
        for(boolean vertical:new boolean[]{true,false})for(boolean rtl:new boolean[]{true,false})
            for(float outer:new float[]{.5f,.75f,1f})for(float fill:new float[]{.1f,.88f,1f})
                for(float progress:new float[]{0,.01f,.02f,.05f,.25f,.5f,.75f,.99f,1f})
                    for(boolean thumb:new boolean[]{true,false}){
                    float w=vertical?100:300,h=vertical?300:100;
                    float x=vertical?0:rtl?w*(1-progress):0,y=vertical?h*(1-progress):0;
                    float sw=vertical?w:w*progress,sh=vertical?h*progress:h;
                    float[] g=QuickSettingsGlass.sliderPrimitive(w,h,outer,fill,x,y,sw,sh,thumb,rtl);
                    for(float v:g)assertTrue(Float.isFinite(v));
                    assertTrue(g[2]>=0&&g[3]>=0&&g[4]>=0);
                    assertTrue(g[4]<=Math.min(g[2],g[3])/2);
                    assertTrue(g[0]>=0&&g[1]>=0&&g[0]+g[2]<=w+.001&&g[1]+g[3]<=h+.001);
                    if(!thumb){
                        if(vertical){assertEquals(y,g[1],.001);assertEquals(sh,g[3],.001);}
                        else{assertEquals(x,g[0],.001);assertEquals(sw,g[2],.001);}
                    }
                }
    }
    @Test public void zeroOneAndTwoPercentHaveAFullCircularHandleAndNoFlattenedBody(){
        for(boolean vertical:new boolean[]{true,false})for(boolean rtl:new boolean[]{true,false})
            for(float outer:new float[]{.5f,1f})for(float fill:new float[]{.1f,.88f,1f})
                for(float p:new float[]{0,.01f,.02f}){
                    float w=vertical?100:300,h=vertical?300:100,d=100*outer*fill;
                    float x=vertical?0:rtl?w*(1-p):0,y=vertical?h*(1-p):0,sw=vertical?w:w*p,sh=vertical?h*p:h;
                    float[] cap=QuickSettingsGlass.sliderPrimitive(w,h,outer,fill,x,y,sw,sh,true,rtl);
                    assertEquals(d,cap[2],.001);assertEquals(d,cap[3],.001);assertEquals(d/2,cap[4],.001);
                    assertTrue(cap[0]>=0&&cap[1]>=0&&cap[0]+d<=w+.001&&cap[1]+d<=h+.001);
                    float[] body=QuickSettingsGlass.sliderPrimitive(w,h,outer,fill,x,y,sw,sh,false,rtl);
                    assertEquals((vertical?sh:sw)>=d&&p>0,QuickSettingsGlass.sliderBodyVisible(body[2],body[3],vertical));
                    // The supplied logical/native progress remains exactly p; only draw geometry changed.
                    assertEquals(p,vertical?sh/h:sw/w,.00001);
                }
    }
    @Test public void invalidSliderInputIsAnEmptyFinitePrimitive(){
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}){
            float[] g=QuickSettingsGlass.sliderPrimitive(bad,100,1,.88f,0,0,0,0,false,false);
            assertArrayEquals(new float[]{0,0,0,0,0},g,0);
        }
        assertEquals(0,QuickSettingsGlass.sliderPrimitive(100,300,1,.88f,0,300,100,0,false,false)[4],0);
    }
    @Test public void thumbRetainsProgressEndAndRtlAnchor(){
        float[] ltr=QuickSettingsGlass.sliderPrimitive(300,100,1,.88f,50,0,100,100,true,false);
        assertEquals(150,ltr[0]+ltr[2],.001);
        float[] rtl=QuickSettingsGlass.sliderPrimitive(300,100,1,.88f,150,0,100,100,true,true);
        assertEquals(150,rtl[0],0);
    }
    @Test public void externalEnvelopeCannotGrowBeyondNativeCell(){
        assertArrayEquals(new float[]{25,0,75,300},QuickSettingsGlass.envelope(100,300,.5f),0);
        assertArrayEquals(new float[]{0,25,300,75},QuickSettingsGlass.envelope(300,100,.5f),0);
        assertArrayEquals(new float[]{0,0,100,300},QuickSettingsGlass.envelope(100,300,2),0);
    }
    private static int over(int fg,int bg){
        float a=(fg>>>24)/255f;int out=0xff000000;
        for(int shift:new int[]{0,8,16})out|=Math.round(((fg>>>shift)&255)*a+((bg>>>shift)&255)*(1-a))<<shift;
        return out;
    }
    private static double luma(int c){double sum=0;double[] weights={.0722,.7152,.2126};
        for(int i=0;i<3;i++){double v=((c>>>(8*i))&255)/255.;sum+=weights[i]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));}return sum;}
    private static double contrast(int a,int b){double x=luma(a),y=luma(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
}

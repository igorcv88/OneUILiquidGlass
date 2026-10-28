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
    @Test public void whiteBackdropIsObsidianInEitherAndroidThemeAndUnknownIsNativeFallback(){
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
    @Test public void defaultObsidianHasMarginForNormalTextEvenOverIdealWhite(){
        int fill=QuickSettingsGlass.fill(CONTAINER,0,true,LIGHT_SCENE,defaults());
        int rendered=over(fill,0xffffffff);
        assertTrue(contrast(0xffffffff,rendered)>4.5);
        assertTrue(contrast(over(QuickSettingsGlass.ink(0x99ffffff,false),rendered),rendered)>4.5);
    }
    @Test public void lightComparatorHasDarkInkAndLiveValuesHaveDistinctSnapshots(){
        QsStyle a=defaults(),b=new QsStyle(k->k.equals("qs.lightstyle")?"light":null);
        assertNotEquals(a,b);assertTrue(b.lightGlass);
        assertTrue(contrast(0xff1d1d1d,over(QuickSettingsGlass.fill(CONTAINER,0,true,LIGHT_SCENE,b),0xffffffff))>4.5);
        assertNotEquals(a,new QsStyle(k->k.equals("qs.fill")?"0.25":null));
        assertEquals(a,new QsStyle(k->k.equals("qs.fill")?"NaN":null));
    }
    @Test public void independentThicknessKeepsProgressCoordinatesAndStaysInsideCell(){
        for(boolean vertical:new boolean[]{true,false})for(float outer:new float[]{.5f,1f})
            for(float track:new float[]{.1f,.88f,1f})for(float fill:new float[]{.1f,.88f,1f})
                for(float progress:new float[]{0,.5f,1f}){
                    float w=vertical?100:300,h=vertical?300:100;
                    float x=0,y=vertical?h*(1-progress):0;
                    float sw=vertical?w:w*progress,sh=vertical?h*progress:h;
                    float[] g=QuickSettingsGlass.sliderPrimitive(w,h,outer,fill,x,y,sw,sh,false,false);
                    if(vertical){assertEquals(y,g[1],0);assertEquals(sh,g[3],0);assertTrue(g[2]<=w*outer);}
                    else{assertEquals(x,g[0],0);assertEquals(sw,g[2],0);assertTrue(g[3]<=h*outer);}
                    assertTrue(g[0]>=0&&g[1]>=0&&g[0]+g[2]<=w+.001&&g[1]+g[3]<=h+.001);
                    float[] t=QuickSettingsGlass.sliderPrimitive(w,h,outer,track,0,0,w,h,false,false);
                    assertEquals(Math.min(w,h)*outer*track,Math.min(t[2],t[3]),.001);
                }
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

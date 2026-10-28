package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind.*;

public class QsAdaptiveTest {
    private QsStyle defaults(){return new QsStyle(k->null);}
    private static final float[] PROGRESS={0,.01f,.02f,.05f,.25f,.5f,.75f,.99f,1f};

    @Test public void sceneHasHysteresisCooldownAndRejectsInvalidSamples(){
        QsScene s=new QsScene();assertEquals(UNKNOWN,s.tone());
        assertFalse(s.accept(Float.NaN,0));assertFalse(s.accept(-1,0));
        assertTrue(s.accept(.9f,0));assertEquals(LIGHT_SCENE,s.tone());
        assertFalse(s.accept(.2f,499));assertFalse(s.accept(.7f,500));
        assertTrue(s.accept(.64f,501));assertEquals(DARK_SCENE,s.tone());
        assertFalse(s.accept(.74f,1001));assertTrue(s.accept(.76f,1002));
        s.reset();assertEquals(UNKNOWN,s.tone());
        assertEquals(UNKNOWN,QsScene.classify(Float.NaN,LIGHT_SCENE));
        assertEquals(LIGHT_SCENE,QsScene.classify(.7f,LIGHT_SCENE));
        assertEquals(DARK_SCENE,QsScene.classify(.7f,DARK_SCENE));
    }
    /** Dark, coloured or unknown backdrop: transparent glass. Light: a faint dark tint, never a white film. */
    @Test public void capsulesAreGlassWithAFaintDarkTintOnlyOverLightBackdrops(){
        QsStyle s=defaults();
        for(int nativeFill:new int[]{0x33000000,0x1affffff,0xfff2f2f2}){
            assertEquals(0,QuickSettingsGlass.fill(CONTAINER,nativeFill,UNKNOWN,s)>>>24);
            assertEquals(0,QuickSettingsGlass.fill(CONTAINER,nativeFill,DARK_SCENE,s)>>>24);
            int light=QuickSettingsGlass.fill(CONTAINER,nativeFill,LIGHT_SCENE,s);
            assertEquals(0x141823,light&0xffffff);
            assertTrue((light>>>24)>0&&(light>>>24)<=Math.round(.18f*255));
            // Discs and placeholders keep Samsung's own colour in every scene.
            for(QsScene.Tone scene:QsScene.Tone.values()){
                assertEquals(nativeFill,QuickSettingsGlass.fill(ACTIVE,nativeFill,scene,s));
                assertEquals(nativeFill,QuickSettingsGlass.fill(IDLE,nativeFill,scene,s));
                assertEquals(nativeFill,QuickSettingsGlass.fill(NONE,nativeFill,scene,s));
            }
        }
    }

    /** The outline is the white optical rim in every scene: never darkened. */
    @Test public void rimIsWhiteInEveryScene(){
        QsStyle s=defaults();
        for(QsScene.Tone scene:QsScene.Tone.values())for(boolean theme:new boolean[]{true,false})
            assertEquals(0xffffff,QuickSettingsGlass.rim(theme,scene,s)&0xffffff);
        assertEquals(QuickSettingsGlass.rim(true),QuickSettingsGlass.rim(true,UNKNOWN,s));
    }

    @Test public void placeholdersAndActiveStateColoursArePreserved(){
        QsStyle s=defaults();
        assertEquals(0,QuickSettingsGlass.fill(NONE,0,LIGHT_SCENE,s));
        assertEquals(0xff3e91ff,QuickSettingsGlass.fill(ACTIVE,0xff3e91ff,LIGHT_SCENE,s));
        assertEquals(0x99ffffff,QuickSettingsGlass.fill(ACTIVE,0x99ffffff,DARK_SCENE,s));
        assertEquals(0xff12a0ef,QuickSettingsGlass.ink(0xff12a0ef,true));
    }

    @Test public void knownInactiveDarkGlyphChangesInkWithoutInvertingAnActiveDiscGlyph(){
        assertEquals(0xffffffff,QuickSettingsGlass.iconInk(0xff1d1d1d,IDLE,false));
        assertEquals(0xff1d1d1d,QuickSettingsGlass.iconInk(0xff1d1d1d,ACTIVE,false));
        assertEquals(0xff1d1d1d,QuickSettingsGlass.iconInk(0xff1d1d1d,NONE,false));
        assertEquals(0xff12a0ef,QuickSettingsGlass.iconInk(0xff12a0ef,IDLE,false));
    }
    /** Contrast composited on the real backdrop: capsule tint (light only) under the adapted label. */
    @Test public void foregroundContrastIsCompositedOnRealWhiteBlackAndBlueBackdrops(){
        for(int bg:new int[]{0xffffffff,0xff000000,0xff0000ff}){
            boolean light=bg==0xffffffff;
            int capsule=QuickSettingsGlass.fill(CONTAINER,0x33000000,light?LIGHT_SCENE:DARK_SCENE,defaults());
            int ink=QuickSettingsGlass.ink(0xffffffff,light);
            assertTrue(contrast(over(ink,over(capsule,bg)),over(capsule,bg))>=4.5);
        }
    }

    @Test public void transitionalInkKeepsAlphaAndAccents(){
        assertEquals(0x80ffffff,QuickSettingsGlass.ink(0x80eeeeee,0f));
        assertEquals(0x801d1d1d,QuickSettingsGlass.ink(0x80eeeeee,1f));
        int mid=QuickSettingsGlass.ink(0xffffffff,.5f);
        assertEquals(0xff,mid>>>24);assertTrue((mid&255)>0x1d&&(mid&255)<0xff);
        assertEquals(0xff12a0ef,QuickSettingsGlass.ink(0xff12a0ef,.5f));
        assertEquals(0,QuickSettingsGlass.ink(0,.5f));
    }
    @Test public void legacyProfilesCannotRestoreAFilmOrVisualTrack(){
        QsStyle a=defaults();
        String[][] retired={{"qs.lightstyle","obsidian"},{"qs.track",".1"},{"qs.darkfill","1"},{"qs.idle",".12"},
                {"qs.darkcolor","000000"},{"qs.active",".2"}};
        for(String[] kv:retired){
            QsStyle old=new QsStyle(k->k.equals(kv[0])?kv[1]:null);
            assertEquals(kv[0],a,old);
            assertEquals(0,QuickSettingsGlass.fill(CONTAINER,0x33000000,DARK_SCENE,old)>>>24);
        }
        // An old heavy light veil is capped: a tint, never the opaque obsidian card again.
        QsStyle heavy=new QsStyle(k->k.equals("qs.lightfill")?".66":null);
        assertEquals(.18f,heavy.lightFill,1e-6f);
        assertEquals(0,QuickSettingsGlass.fill(CONTAINER,0x33000000,DARK_SCENE,heavy)>>>24);
        assertNotEquals(a,new QsStyle(k->k.equals("qs.fill")?"0.25":null));
        assertEquals(a,new QsStyle(k->k.equals("qs.fill")?"NaN":null));
        assertEquals(.78f,a.fill,1e-6f);
    }

    private static float[] primitive(float w,float h,float outer,float fill,float x,float y,float sw,float sh,boolean thumb,float[] body){
        return QuickSettingsGlass.sliderPrimitive(w,h,outer,fill,x,y,sw,sh,thumb,body,new float[5]);
    }

    /**
     * 0-100 %, vertical and horizontal, LTR and RTL, every permitted thickness: the progress is one
     * rounded shape, never flattened, inside the glass, at the native value, and the thumb is its
     * end cap (no third surface).
     */
    @Test public void twoSurfaceGeometryAcrossRangeDirectionsAndPermittedThickness(){
        for(boolean vertical:new boolean[]{true,false})for(boolean rtl:new boolean[]{true,false})
            for(float outer:new float[]{.5f,.75f,1f})for(float fill:new float[]{.1f,.5f,.78f,1f})
                for(float p:PROGRESS){
                    float w=vertical?100:300,h=vertical?300:100,len=vertical?h:w;
                    float d=Math.min(w,h)*outer*fill;
                    // Native body: from the bottom (vertical), from the start edge (LTR) or the end (RTL).
                    float x=vertical?0:rtl?w*(1-p):0,y=vertical?h*(1-p):0;
                    float sw=vertical?w:w*p,sh=vertical?h*p:h;
                    float[] body=primitive(w,h,outer,fill,x,y,sw,sh,false,null);
                    float[] cap=primitive(w,h,outer,fill,x,y,sw,sh,true,body);
                    for(float[] g:new float[][]{body,cap}){
                        for(float v:g)assertTrue(Float.isFinite(v));
                        assertTrue(g[2]>0&&g[3]>0);
                        assertEquals(Math.min(g[2],g[3])/2,g[4],1e-4);
                        assertTrue(g[0]>=-1e-3&&g[1]>=-1e-3&&g[0]+g[2]<=w+1e-3&&g[1]+g[3]<=h+1e-3);
                        // Centred thickness: the same margin on both sides of the narrow axis.
                        assertEquals(d,vertical?g[2]:g[3],1e-3);
                        assertEquals((vertical?w:h)-d,2*(vertical?g[0]:g[1]),1e-3);
                    }
                    float lo=vertical?body[1]:body[0],hi=lo+(vertical?body[3]:body[2]);
                    float nativeLen=len*p;
                    // Never flattened: at least one diameter; the native length otherwise.
                    assertEquals(Math.max(d,nativeLen),hi-lo,1e-3);
                    // Anchored at the native start edge.
                    if(vertical)assertEquals(h,hi,1e-3);else if(rtl)assertEquals(w,hi,1e-3);else assertEquals(0,lo,1e-3);
                    // The cap is a circle at the moving end, inside the body: one white shape.
                    assertEquals(cap[2],cap[3],1e-3);
                    float clo=vertical?cap[1]:cap[0],chi=clo+(vertical?cap[3]:cap[2]);
                    assertTrue(clo>=lo-1e-3&&chi<=hi+1e-3);
                    // At 100 % both ends touch the bar and either is the moving one; the cap is inside anyway.
                    if(p<1){if(vertical||rtl)assertEquals(lo,clo,1e-3);else assertEquals(hi,chi,1e-3);}
                    // The value shown matches the native value once it exceeds one diameter.
                    if(nativeLen>=d){
                        if(vertical)assertEquals(y,lo,1e-3);else if(rtl)assertEquals(x,lo,1e-3);else assertEquals(sw,hi,1e-3);
                    }
                }
    }
    @Test public void minimumIsACircleThatStaysStableAcrossTheFirstPercent(){
        for(boolean vertical:new boolean[]{true,false}){
            float w=vertical?100:300,h=vertical?300:100;float[] first=null;
            for(float p:new float[]{0,.001f,.01f,.02f}){
                float[] body=primitive(w,h,1,.78f,0,vertical?h*(1-p):0,vertical?w:w*p,vertical?h*p:h,false,null);
                assertEquals(78,body[2],1e-3);assertEquals(78,body[3],1e-3);assertEquals(39,body[4],1e-3);
                if(first==null)first=body.clone();else assertArrayEquals(first,body,1e-3f);
            }
        }
    }
    @Test public void thumbWithoutABodyThisFrameUsesTheNativeCentreInsideTheBar(){
        float[] cap=primitive(300,100,1,.78f,280,0,40,100,true,null);
        assertEquals(78,cap[2],1e-3);assertEquals(300,cap[0]+cap[2],1e-3);
        cap=primitive(300,100,1,.78f,-30,0,40,100,true,null);
        assertEquals(0,cap[0],1e-3);
    }
    @Test public void invalidSliderInputIsAnEmptyPrimitive(){
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}){
            float[] g=primitive(bad,100,1,.78f,0,0,0,0,false,null);
            assertArrayEquals(new float[]{0,0,0,0,0},g,0);
            g=primitive(100,300,1,.78f,bad,0,0,0,false,null);
            if(!Float.isFinite(bad))assertArrayEquals(new float[]{0,0,0,0,0},g,0);
        }
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

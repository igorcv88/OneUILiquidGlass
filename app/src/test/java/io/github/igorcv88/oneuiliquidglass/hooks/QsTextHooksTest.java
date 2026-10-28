package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.*;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class QsTextHooksTest {
    @Test public void repeatedRenderTransitionsRestoreNativePaintAndNeverNeedTextStyle(){
        Paint paint=new Paint();paint.setColor(0xffffffff);
        for(int i=0;i<100;i++)for(Boolean tone:new Boolean[]{true,false,null}){
            int original=paint.getColor();
            Integer saved=QsTextHooks.applyPaint(paint,tone,true);
            if(Boolean.TRUE.equals(tone))assertEquals(0xff1d1d1d,paint.getColor());
            if(saved!=null)paint.setColor(saved); // the hook's unconditional after callback
            assertEquals(original,paint.getColor());
        }
        assertNull(QsTextHooks.applyPaint(paint,true,false));assertEquals(0xffffffff,paint.getColor());
    }
    @Test public void headerIconFilterIsTemporaryAndDoesNotNeedIconRecomposition(){
        QsTextHooks hooks=new QsTextHooks(getClass().getClassLoader(),()->null,()->true,()->QsScene.Tone.LIGHT_SCENE,()->QsScene.Tone.LIGHT_SCENE);
        Paint paint=new Paint();ColorFilter nativeFilter=new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN);
        paint.setColorFilter(nativeFilter);paint.setAlpha(123);
        ColorFilter original=hooks.applyIconPaint(paint,true,true);assertSame(nativeFilter,original);
        ColorFilter applied=paint.getColorFilter();assertNotSame(nativeFilter,applied);assertEquals(123,paint.getAlpha());
        paint.setColorFilter(original);assertNull(hooks.applyIconPaint(paint,true,false));
        assertSame(nativeFilter,paint.getColorFilter());
        hooks.applyIconPaint(paint,true,true);assertSame(applied,paint.getColorFilter());paint.setColorFilter(nativeFilter);
        paint.setColorFilter(new PorterDuffColorFilter(0xff00aaee,PorterDuff.Mode.SRC_IN));
        assertNull(hooks.applyIconPaint(paint,true,true));paint.setColorFilter(null);
        assertNull(hooks.applyIconPaint(paint,true,true));
    }
    @Test public void colouredSpansShadersAndFilteredPaintStayNative(){
        Paint paint=new Paint();paint.setColor(0xff00aaee);
        assertNull(QsTextHooks.applyPaint(paint,true,true));
        paint.setColor(0xffffffff);paint.setColorFilter(new PorterDuffColorFilter(0xff00aaee,PorterDuff.Mode.SRC_IN));
        assertNull(QsTextHooks.applyPaint(paint,true,true));paint.setColorFilter(null);
        paint.setShader(new LinearGradient(0,0,10,10,0xffffffff,0xff000000,Shader.TileMode.CLAMP));
        assertNull(QsTextHooks.applyPaint(paint,true,true));
    }
    @Test public void unknownDisabledAndUnrelatedNodesHaveNoRenderOverride() throws Exception {
        boolean[] enabled={true};QsScene.Tone[] scene={QsScene.Tone.LIGHT_SCENE};
        QsTextHooks hooks=new QsTextHooks(getClass().getClassLoader(),()->null,()->enabled[0],()->scene[0],()->scene[0]);
        Object qs=new Object(),other=new Object();hooks.bindNode(qs,false);
        assertNull(hooks.toneFor(qs)); // incompatible/not installed
        Field ready=QsTextHooks.class.getDeclaredField("ready");ready.setAccessible(true);ready.set(hooks,true);
        for(boolean quicksettings:new boolean[]{true,false}){
            enabled[0]=quicksettings;
            assertEquals(quicksettings?Boolean.TRUE:null,hooks.toneFor(qs));
            assertNull(hooks.toneFor(other));
        }
        enabled[0]=true;scene[0]=QsScene.Tone.DARK_SCENE;assertEquals(Boolean.FALSE,hooks.toneFor(qs));
        scene[0]=QsScene.Tone.UNKNOWN;assertNull(hooks.toneFor(qs));
        // A recomposition from an element built outside the allowlisted owners keeps the node QS's.
        hooks.bindNode(qs,null);scene[0]=QsScene.Tone.LIGHT_SCENE;assertEquals(Boolean.TRUE,hooks.toneFor(qs));
        hooks.bindNode(other,null);assertNull(hooks.toneFor(other));
    }
    @Test public void transitionLevelsInterpolateNeutralInkAndRestoreThePaint(){
        Paint paint=new Paint();paint.setColor(0xb3ffffff);
        int last=0xff;
        for(float level=0f;level<=1.0001f;level+=.1f){
            Integer saved=QsTextHooks.applyPaint(paint,Math.min(1f,level),true);
            int c=paint.getColor();
            assertEquals(0xb3,c>>>24);
            assertTrue((c&255)<=last);last=c&255;
            if(saved!=null)paint.setColor(saved);
            assertEquals(0xb3ffffff,paint.getColor());
        }
        assertEquals(0x1d,last);
        paint.setColor(0xff00aaee);assertNull(QsTextHooks.applyPaint(paint,.5f,true));
    }
    @Test public void nodeInkFollowsTheAnimatedLevelPerRegion() throws Exception {
        float[] scene={.25f},header={1f};
        QsTextHooks hooks=new QsTextHooks(getClass().getClassLoader(),()->null,()->true,()->QsScene.Tone.LIGHT_SCENE,()->QsScene.Tone.LIGHT_SCENE,
                h->h?header[0]:scene[0]);
        Field ready=QsTextHooks.class.getDeclaredField("ready");ready.setAccessible(true);ready.set(hooks,true);
        Object body=new Object(),head=new Object();hooks.bindNode(body,false);hooks.bindNode(head,true);
        assertEquals(.25f,hooks.inkFor(body),0f);assertEquals(1f,hooks.inkFor(head),0f);
        assertEquals(Boolean.FALSE,hooks.toneFor(body));assertEquals(Boolean.TRUE,hooks.toneFor(head));
        scene[0]=.75f;assertEquals(.75f,hooks.inkFor(body),0f);
    }
    /** QS glass off (the default), shade glass on: the header still takes the shade's ink. */
    @Test public void headerFollowsTheShadeWithQuickSettingsGlassOff() throws Exception {
        float[] header={1f};
        QsTextHooks hooks=new QsTextHooks(getClass().getClassLoader(),()->null,()->false,()->QsScene.Tone.UNKNOWN,()->QsScene.Tone.UNKNOWN,
                h->h?Float.valueOf(header[0]):null);
        Field ready=QsTextHooks.class.getDeclaredField("ready");ready.setAccessible(true);ready.set(hooks,true);
        Object clock=new Object(),label=new Object();hooks.bindNode(clock,true);hooks.bindNode(label,false);
        assertEquals(1f,hooks.inkFor(clock),0f);
        assertNull("body labels stay QS-only",hooks.inkFor(label));
        Paint paint=new Paint();paint.setColor(0xffffffff);
        assertNotNull(QsTextHooks.applyPaint(paint,hooks.inkFor(clock),true));
        assertEquals(0xff1d1d1d,paint.getColor());
    }

    /** The visible clock is identified by the shape of what a header node draws, not by its class. */
    @Test public void clockShapeIsRecognisedForEveryCanvasTextForm(){
        assertTrue(QsTextHooks.clockText(new Object[]{"18:44",0f,0f,null}));
        assertTrue(QsTextHooks.clockText(new Object[]{"6:44 PM",0f,0f,null}));
        assertTrue(QsTextHooks.clockText(new Object[]{"x18:44y",1,6,0f,0f,null}));
        assertTrue(QsTextHooks.clockText(new Object[]{"..18:44".toCharArray(),2,5,0f,0f,null}));
        assertTrue(QsTextHooks.clockText(new Object[]{new StringBuilder("09.05"),0,5,0f,0f,null}));
        assertFalse(QsTextHooks.clockText(new Object[]{"Wed, Oct 28",0f,0f,null}));
        assertFalse(QsTextHooks.clockText(new Object[]{"Wi-Fi",0f,0f,null}));
        assertFalse(QsTextHooks.clockText(new Object[]{"18:44".toCharArray(),3,9,0f,0f,null}));
        assertFalse(QsTextHooks.clockText(new Object[]{"18:44",4,2,0f,0f,null}));
        assertFalse(QsTextHooks.clockText(new Object[0]));
        assertFalse(QsTextHooks.clockText(null));
    }
}

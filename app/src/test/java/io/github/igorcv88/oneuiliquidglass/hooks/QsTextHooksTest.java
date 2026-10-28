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
        hooks.bindNode(qs,null);scene[0]=QsScene.Tone.LIGHT_SCENE;assertNull(hooks.toneFor(qs));
    }
}

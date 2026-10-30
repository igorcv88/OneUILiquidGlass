package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class QsTextHooksTest {
    private static final class FakeBlendModeColorFilter {
        final long color;
        FakeBlendModeColorFilter(int tint){color=QuickSettingsGlass.color(tint);}
    }
    private static final class FakeBlendTintConstructor {
        final long color;
        final int blend;
        FakeBlendTintConstructor(long color,int blend){this.color=color;this.blend=blend;}
    }
    private static final class FakeNativeTintConstructor {
        final ColorFilter colorFilter;
        FakeNativeTintConstructor(ColorFilter colorFilter){this.colorFilter=colorFilter;}
    }
    private static final class FakeNativeColorFilter {
        final ColorFilter colorFilter;
        FakeNativeColorFilter(int argb){colorFilter=new PorterDuffColorFilter(argb,PorterDuff.Mode.SRC_IN);}
    }
    private static final class UnrelatedConstructor {
        UnrelatedConstructor(String ignored){}
    }
    @Test public void composeTintConstructorSurvivesAbsentCompanionBridge() throws Exception {
        Constructor<?> ctor=QsTextHooks.blendTintConstructor(FakeBlendTintConstructor.class);
        assertNotNull(ctor);
        FakeBlendTintConstructor blend=(FakeBlendTintConstructor)
                QsTextHooks.constructComposeTint(ctor,false,0xffffffff);
        assertEquals(QuickSettingsGlass.color(0xffffffff),blend.color);
        assertEquals(5,blend.blend); // Compose BlendMode.SrcIn
        assertNull(QsTextHooks.blendTintConstructor(UnrelatedConstructor.class));

        Constructor<?> nativeCtor=QsTextHooks.nativeTintConstructor(FakeNativeTintConstructor.class);
        assertNotNull(nativeCtor);
        FakeNativeTintConstructor nativeTint=(FakeNativeTintConstructor)
                QsTextHooks.constructComposeTint(nativeCtor,true,0xff112233);
        assertTrue(nativeTint.colorFilter instanceof PorterDuffColorFilter);
        assertEquals(Integer.valueOf(0xff112233),
                Foreground.filterColor(nativeTint.colorFilter));
        assertNull(QsTextHooks.nativeTintConstructor(UnrelatedConstructor.class));
    }
    @Test public void composeFilterGateInspectsNeutralTintAndLeavesColoredLogosUntouched(){
        QsTextHooks hooks=new QsTextHooks(getClass().getClassLoader(),()->null,()->true,
                ()->QsScene.Tone.LIGHT_SCENE,()->QsScene.Tone.LIGHT_SCENE);
        assertTrue(hooks.neutralComposeFilter(new FakeBlendModeColorFilter(0xff000000)));
        assertTrue(hooks.neutralComposeFilter(new FakeBlendModeColorFilter(0xffffffff)));
        assertFalse(hooks.neutralComposeFilter(new FakeBlendModeColorFilter(0xff00aaff)));
        assertTrue(hooks.neutralComposeFilter(new FakeNativeColorFilter(0xffd8d8d8)));
        assertFalse(hooks.neutralComposeFilter(new FakeNativeColorFilter(0xff00aaff)));
        assertFalse(hooks.neutralComposeFilter(new Object()));
        assertFalse(hooks.neutralComposeFilter(null));
    }
    @Test public void composeTintIncludesVerifiedActiveInactiveAndDiscLessIconsOnLightBackground(){
        assertTrue(QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,true));
        assertFalse(QsTextHooks.composeTileEligible(QsScene.Tone.DARK_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,true));
        assertFalse(QsTextHooks.composeTileEligible(QsScene.Tone.UNKNOWN,
                QuickSettingsGlass.Kind.ACTIVE,true,true));
        assertTrue(QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.IDLE,true,true));
        assertTrue(QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.NONE,true,true));
        assertFalse(QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.CONTAINER,true,true));
        assertFalse(QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,false,true));
        assertFalse("untinted or coloured icon",QsTextHooks.composeTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,false));
    }
    @Test public void unfilteredComposeFallbackRequiresBoundQuickTileAndVectorForInactive(){
        String owner="tiles.QuickTileKt$$ExternalSyntheticLambda14";
        assertTrue(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,owner,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,owner,false)); // native filter isn't absent
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,null,true)); // unbound model/owner
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,"tiles.QuickTileBoxKt",true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,"tiles.BrightBarKt",true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,false,owner,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.DARK_SCENE,
                QuickSettingsGlass.Kind.ACTIVE,true,owner,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.UNKNOWN,
                QuickSettingsGlass.Kind.ACTIVE,true,owner,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.NONE,true,owner,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.IDLE,true,owner,true));
        assertTrue(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.IDLE,true,owner,true,true));
        assertTrue(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.NONE,true,owner,true,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.NONE,true,owner,false,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.LIGHT_SCENE,
                QuickSettingsGlass.Kind.IDLE,true,"tiles.QuickTileBoxKt",true,true));
        assertFalse(QsTextHooks.composeUnfilteredQuickTileEligible(QsScene.Tone.DARK_SCENE,
                QuickSettingsGlass.Kind.IDLE,true,owner,true,true));
        String deferred="tiles.IconTextTile.deferred";
        assertTrue("verified deferred icon vector",QsTextHooks.composeUnfilteredQuickTileEligible(
                QsScene.Tone.LIGHT_SCENE,QuickSettingsGlass.Kind.NONE,true,deferred,true,true));
        assertFalse("do not recolor deferred bitmap art",QsTextHooks.composeUnfilteredQuickTileEligible(
                QsScene.Tone.LIGHT_SCENE,QuickSettingsGlass.Kind.ACTIVE,true,deferred,true,false));
    }
    @Test public void deferredModelBindingAcceptsOnlySamsungComposeLambdaType(){
        assertTrue(QsTileProbe.tileLambdaType("androidx.compose.runtime.internal.ComposableLambdaImpl"));
        assertFalse(QsTileProbe.tileLambdaType("androidx.compose.foundation.text.BasicTextKt"));
        assertFalse(QsTileProbe.tileLambdaType(null));
    }
    @Test public void headerComposeTintRequiresHeaderProvenanceAndMonochromePainter(){
        String header="panel.SecQuickSettingHeaderKt$$ExternalSyntheticLambda9";
        assertTrue(QsTextHooks.composeHeaderEligible(1f,header,true,false));
        assertTrue(QsTextHooks.composeHeaderEligible(0f,header,false,true));
        assertTrue(QsTextHooks.composeHeaderEligible(.5f,header,true,false));
        assertFalse(QsTextHooks.composeHeaderEligible(null,header,true,true));
        assertFalse(QsTextHooks.composeHeaderEligible(Float.NaN,header,true,true));
        assertFalse(QsTextHooks.composeHeaderEligible(1f,"tiles.QuickTileKt",true,true));
        assertFalse(QsTextHooks.composeHeaderEligible(1f,null,true,true));
        assertFalse(QsTextHooks.composeHeaderEligible(1f,header,false,false));
        assertTrue(QsTextHooks.isComposeVectorPainter("androidx.compose.ui.graphics.vector.VectorPainter"));
        assertFalse(QsTextHooks.isComposeVectorPainter("androidx.compose.ui.graphics.painter.BitmapPainter"));
        assertFalse(QsTextHooks.isComposeVectorPainter("androidx.compose.ui.graphics.painter.ColorPainter"));
        assertFalse(QsTextHooks.isComposeVectorPainter("unknown"));
        assertEquals(0xff1d1d1d,QuickSettingsGlass.ink(0xffffffff,1f));
        assertEquals(0xffffffff,QuickSettingsGlass.ink(0xffffffff,0f));
    }
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
    @Test public void onlyQuickSettingsLabelsLoseNativeGreyAlpha(){
        Paint p=new Paint();p.setColor(0x80ffffff);
        Integer before=QsTextHooks.applyLabelPaint(p,1f,true);
        assertEquals(0x80ffffff,before.intValue());
        assertEquals(0xff1d1d1d,p.getColor());
        p.setColor(before);
        assertEquals(0x80ffffff,p.getColor());
        assertNull(QsTextHooks.applyPaint(p,0f,true));
        assertEquals("header paint still has native alpha",0x80ffffff,p.getColor());
        p.setColor(0x801d1d1d);
        assertEquals(0x801d1d1d,QsTextHooks.applyLabelPaint(p,1f,true).intValue());
        assertEquals(0xff1d1d1d,p.getColor());
        p.setColor(0x8012a0ef);
        assertNull("colour accents remain native",QsTextHooks.applyLabelPaint(p,1f,true));
        assertEquals(0x8012a0ef,p.getColor());
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

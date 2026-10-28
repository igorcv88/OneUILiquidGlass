package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class QsRefreshTest {
    private Object previous;
    @Before public void save() throws Exception {previous=field(ConfigClient.class,"current").get(null);setValues(".10");}
    @After public void restore() throws Exception {field(ConfigClient.class,"current").set(null,previous);}
    @Test public void numericChangeWhileOnInvalidatesOnlyValidCapturedScopes() throws Exception {
        QuickSettingsHooks hooks=new QuickSettingsHooks(getClass().getClassLoader(),()->true,context->{});
        field(QuickSettingsHooks.class,"ready").set(hooks,true);
        field(QuickSettingsHooks.class,"applied").set(hooks,true);
        field(QuickSettingsHooks.class,"invalidate").set(hooks,Scope.class.getMethod("invalidateForResult",Object.class));
        field(QuickSettingsHooks.class,"valid").set(hooks,Scope.class.getMethod("getValid"));
        Scope live=new Scope(true),stale=new Scope(false);
        @SuppressWarnings("unchecked") Set<Object> scopes=(Set<Object>)field(QuickSettingsHooks.class,"scopes").get(hooks);
        scopes.add(live);scopes.add(stale);
        hooks.refresh();assertEquals(0,live.invalidations);
        setValues(".20");hooks.refresh();assertEquals(1,live.invalidations);assertEquals(0,stale.invalidations);
        hooks.refresh();assertEquals(1,live.invalidations);
    }
    @Test public void quickSettingsGateIsIndependentInAllFourSurfaceStates() throws Exception {
        java.lang.reflect.Method gate=QuickSettingsHooks.class.getDeclaredMethod("enabled");gate.setAccessible(true);
        for(boolean qs:new boolean[]{true,false})for(boolean notifications:new boolean[]{true,false}){
            Map<String,String> values=new HashMap<>();
            values.put("surface.quicksettings.enabled",qs?"1":"0");values.put("surface.shade.enabled",notifications?"1":"0");
            field(ConfigClient.class,"current").set(null,values);
            QuickSettingsHooks hooks=new QuickSettingsHooks(getClass().getClassLoader(),()->true,context->{});
            assertEquals(qs,gate.invoke(hooks));
        }
    }
    @Test public void onlyTheTrackIsSuppressedAndNativeLogicalInputsAreUntouched(){
        assertTrue(QsSliderHooks.suppressTrack(0));
        assertFalse(QsSliderHooks.suppressTrack(1));assertFalse(QsSliderHooks.suppressTrack(2));
    }
    @Test public void trackGeometryOverrideKeepsTheNativeAlphaDefaultBit() {
        // DZIF passes alpha=0 with bit 8 meaning "use 1". Clearing it makes the track invisible.
        assertEquals(104,QsSliderHooks.explicitGeometryMask(110,false));
        assertEquals(8,QsSliderHooks.explicitGeometryMask(110,false)&8);
        assertEquals(208,QsSliderHooks.explicitGeometryMask(208,true));
    }
    @Test public void cachedArtworkDisplayListIsInvalidatedAndUnrelatedRebindReleasesIt() throws Exception {
        QsContentHooks content=new QsContentHooks(getClass().getClassLoader(),()->true,()->null,
                ()->io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.UNKNOWN,
                ()->io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.UNKNOWN,
                ()->io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind.NONE,scope->{});
        field(QsContentHooks.class,"invalidateDraw").set(content,DrawNode.class.getMethod("invalidate",Object.class));
        DrawNode node=new DrawNode();content.bindArtwork(node,true);content.bindArtwork(node,true);
        content.invalidateArtwork();assertEquals(1,node.invalidations);
        content.invalidateArtwork();assertEquals(2,node.invalidations);
        content.bindArtwork(node,false);content.invalidateArtwork();assertEquals(2,node.invalidations);
    }
    public static class DrawNode {
        int invalidations;
        public static void invalidate(Object node){((DrawNode)node).invalidations++;}
    }
    private void setValues(String fill) throws Exception {
        Map<String,String> values=new HashMap<>();values.put("surface.quicksettings.enabled","1");values.put("qs.darkfill",fill);
        field(ConfigClient.class,"current").set(null,values);
    }
    private static Field field(Class<?> c,String name) throws Exception {Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    public static class Scope {
        final boolean valid;int invalidations;Scope(boolean valid){this.valid=valid;}
        public boolean getValid(){return valid;}
        public Object invalidateForResult(Object value){invalidations++;return null;}
    }
}

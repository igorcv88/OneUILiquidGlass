package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Looper;
import android.widget.*;
import java.time.Duration;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class HeaderToneControllerTest {
    Activity activity;LinearLayout root;Map<String,TextView> targets;HeaderToneController ink;
    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class).setup().get();
        root=new LinearLayout(activity);activity.setContentView(root);targets=new HashMap<>();
        ink=new HeaderToneController((host,name)->targets.get(name));}
    @After public void close(){ink.clear();activity.finish();}
    private TextView add(String id,int color){TextView t=new TextView(activity);t.setTextColor(color);root.addView(t);targets.put(id,t);return t;}
    private void draw(){root.getViewTreeObserver().dispatchOnPreDraw();}
    private static void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    private static long serial=100;
    /** Four 100 px bands: {@code top} for the first, {@code rest} below; each call is a new capture. */
    private ToneGrid grid(float top,float rest,int generation) throws Exception {
        java.lang.reflect.Constructor<ToneGrid> c=ToneGrid.class.getDeclaredConstructor(int.class,int.class,float[].class,float[].class,int.class,long.class);
        c.setAccessible(true);return c.newInstance(0,100,new float[]{top,rest,rest,rest},new float[]{top,rest,rest,rest},generation,serial++);
    }
    /** Mixed backdrop: light behind the header, dark elsewhere; the header's own band decides. */
    @Test public void mixedBackdropIsDecidedFromTheHeadersBandNotTheWholeScreen() throws Exception {
        TextView clock=add("header_clock",0xffffffff);root.layout(0,0,500,400);clock.layout(0,0,100,50);
        ink.update(root,grid(1,0,1));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        ink.clear();clock.setTextColor(0xffffffff);
        ink.update(root,grid(0,1,2));assertEquals(0xffffffff,clock.getCurrentTextColor());
    }
    /** The opening animation moves the header; no frame of that motion re-decides its tone. */
    @Test public void movementWithoutANewSampleNeverFlipsTheTone() throws Exception {
        TextView clock=add("header_clock",0xffffffff),date=add("header_date",0xffffffff);
        root.layout(0,0,500,400);clock.layout(0,0,100,50);date.layout(100,0,200,50);
        ink.update(root,grid(1,0,1));
        for(int y=0;y<=300;y+=25){
            clock.setTranslationY(y);date.setTranslationY(y);draw();
            assertEquals(0xff1d1d1d,clock.getCurrentTextColor());assertEquals(0xff1d1d1d,date.getCurrentTextColor());
        }
        // A layout move (not a sample) does not decide either.
        clock.layout(0,220,100,270);draw();assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
    }
    @Test public void layoutTopIgnoresTranslations(){
        TextView clock=add("header_clock",0xffffffff);root.layout(0,0,500,400);clock.layout(0,40,100,90);
        clock.setTranslationY(-300);root.setTranslationY(120);
        assertEquals(40,HeaderToneController.layoutTop(clock));
    }
    /** A real change: one contrary sample is not enough; the confirming one moves the ink smoothly. */
    @Test public void realBackdropChangeIsConfirmedThenAnimatedBothWays() throws Exception {
        TextView clock=add("header_clock",0xffffffff);root.layout(0,0,500,400);clock.layout(0,0,100,50);
        ink.update(root,grid(1,1,1));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        idle(500);ink.update(root,grid(0,0,1));assertEquals("unconfirmed",0xff1d1d1d,clock.getCurrentTextColor());
        idle(200);ink.update(root,grid(0,0,1));
        // Confirmed: a transition from the colour on screen, not a jump.
        assertTrue(InkAnimator.running(clock));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        idle(400);assertFalse(InkAnimator.running(clock));assertEquals(0xffffffff,clock.getCurrentTextColor());
        // Noise: a single light reading between dark ones changes nothing.
        idle(500);ink.update(root,grid(1,1,1));idle(200);ink.update(root,grid(0,0,1));idle(400);
        assertEquals(0xffffffff,clock.getCurrentTextColor());
        // And back to light.
        idle(500);ink.update(root,grid(1,1,1));idle(200);ink.update(root,grid(1,1,1));idle(400);
        assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
    }
    @Test public void invalidUnlaidOutAndMissingSamplesLeaveNativeInk() throws Exception {
        TextView clock=add("header_clock",0xffeeeeee);
        ink.update(root,(ToneGrid)null);assertEquals(0xffeeeeee,clock.getCurrentTextColor());
        ink.update(root,grid(1,1,1));assertEquals(0xffeeeeee,clock.getCurrentTextColor());
        root.layout(0,0,500,400);clock.layout(0,0,100,50);
        ink.update(root,grid(Float.NaN,0,2));assertEquals(0xffeeeeee,clock.getCurrentTextColor());
        ink.update(root,grid(1,0,3));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        clock.setTextColor(0x55eeeeee);clock.setEnabled(false);draw();
        assertEquals(0x551d1d1d,clock.getCurrentTextColor());
        ink.clear();assertEquals(0x55eeeeee,clock.getCurrentTextColor());
    }
    /** A sample that lands before layout is decided on the first pre-draw with bounds, against that sample. */
    @Test public void sampleBeforeLayoutIsDecidedOnFirstLaidOutDraw() throws Exception {
        TextView clock=add("header_clock",0xffffffff);
        ink.update(root,grid(1,0,1));assertEquals(0xffffffff,clock.getCurrentTextColor());
        root.layout(0,0,500,400);clock.layout(0,0,100,50);draw();
        assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
    }
    @Test public void lateNativeRebindIsRepairedBeforeDrawAndExactListRestored(){
        TextView clock=add("header_clock",0xffffffff);ink.update(root,true);assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        idle(700);
        ColorStateList nativeList=new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[0]},new int[]{0x88eeeeee,0xffeeeeee});
        clock.setTextColor(nativeList);draw();assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        ink.clear();assertSame(nativeList,clock.getTextColors());
    }
    @Test public void recreatedHeaderIsReacquiredWithoutWalkingEveryFrame(){
        TextView old=add("header_clock",0xffffffff);ink.update(root,true);root.removeView(old);
        TextView replacement=add("header_clock",0xffeeeeee);draw();
        assertEquals(0xff1d1d1d,replacement.getCurrentTextColor());assertEquals(0xffffffff,old.getCurrentTextColor());
        ink.clear();assertEquals(0xffeeeeee,replacement.getCurrentTextColor());
    }
    @Test public void darkSceneAndNativeActionPillsKeepTheirOwnInk(){
        TextView clock=add("header_clock",0xffffffff),action=add("clear_all",0xff1d1d1d);
        ink.update(root,true);ink.update(root,false);idle(400);draw();
        assertEquals(0xffffffff,clock.getCurrentTextColor());assertEquals(0xff1d1d1d,action.getCurrentTextColor());
    }
    /** A rebind mid-transition wins over the transition; the decided ink is re-applied on the next draw. */
    @Test public void rebindDuringTransitionIsNotOverwrittenByStaleFrames(){
        TextView clock=add("header_clock",0xffffffff);
        ink.update(root,true);ink.update(root,false);idle(50);
        clock.setTextColor(0xff1d1d1d);idle(400);draw();
        // Never left on a mid-transition grey: the decided ink is what remains.
        assertEquals(0xffffffff,clock.getCurrentTextColor());
    }
    @Test public void detachRestoresAndCancelsDelayedRepairs(){
        TextView date=add("header_date",0xffeeeeee);ink.update(root,true);
        activity.setContentView(new LinearLayout(activity));
        assertEquals(0xffeeeeee,date.getCurrentTextColor());
        idle(1600);
        assertEquals(0xffeeeeee,date.getCurrentTextColor());
    }
}

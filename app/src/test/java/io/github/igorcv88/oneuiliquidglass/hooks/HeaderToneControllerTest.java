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
    private ToneGrid grid(float top,float rest,int generation) throws Exception {
        java.lang.reflect.Constructor<ToneGrid> c=ToneGrid.class.getDeclaredConstructor(int.class,int.class,float[].class,float[].class,int.class,long.class);
        c.setAccessible(true);return c.newInstance(0,100,new float[]{top,rest,rest,rest},new float[]{top,rest,rest,rest},generation,100L);
    }
    @Test public void mixedBackdropUsesTargetBoundsOnInitialDrawAndAfterExpansion() throws Exception {
        TextView clock=add("header_clock",0xffffffff);root.layout(0,0,500,400);clock.layout(0,0,100,50);
        ink.update(root,grid(1,0,1));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        clock.layout(0,220,100,270);draw();assertEquals(0xffffffff,clock.getCurrentTextColor());
        ink.update(root,grid(0,1,2));assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        clock.layout(0,0,100,50);draw();assertEquals(0xffffffff,clock.getCurrentTextColor());
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
    @Test public void lateNativeRebindIsRepairedBeforeDrawAndExactListRestored(){
        TextView clock=add("header_clock",0xffffffff);ink.update(root,true);assertEquals(0xff1d1d1d,clock.getCurrentTextColor());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
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
        ink.update(root,true);ink.update(root,false);draw();
        assertEquals(0xffffffff,clock.getCurrentTextColor());assertEquals(0xff1d1d1d,action.getCurrentTextColor());
    }
    @Test public void detachRestoresAndCancelsDelayedRepairs(){
        TextView date=add("header_date",0xffeeeeee);ink.update(root,true);
        activity.setContentView(new LinearLayout(activity));
        assertEquals(0xffeeeeee,date.getCurrentTextColor());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        assertEquals(0xffeeeeee,date.getCurrentTextColor());
    }
}

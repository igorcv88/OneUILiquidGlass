package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.widget.*;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Tone changes on a shown card move over a transition, never leave a mid grey, and restore exactly. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ForegroundTransitionTest {
    Activity activity;LinearLayout root;TextView title;List<android.view.View> tracked;
    @Before public void setup(){
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        root=new LinearLayout(activity);activity.setContentView(root);
        title=new TextView(activity);title.setTextColor(0xffffffff);root.addView(title);
        tracked=new ArrayList<>();
    }
    @After public void close(){Foreground.apply(root,false,true,null,false);activity.finish();}
    private static void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}

    @Test public void animatedSwapMovesFromTheShownColourAndIsNotADrift(){
        ColorStateList own=title.getTextColors();
        assertEquals(1,Foreground.apply(root,true,true,tracked,true));
        assertTrue(InkAnimator.running(title));
        assertEquals(0xffffffff,title.getCurrentTextColor());
        assertTrue(tracked.contains(title));
        assertNull("a running transition is not a rebind",Foreground.drift(tracked));
        idle(400);
        assertFalse(InkAnimator.running(title));
        assertEquals(0xff000000,title.getCurrentTextColor());
        Foreground.apply(root,false,true,null,true);idle(400);
        assertEquals(0xffffffff,title.getCurrentTextColor());
        assertEquals(own.getDefaultColor(),title.getTextColors().getDefaultColor());
    }
    @Test public void reversingMidTransitionEndsOnTheLastDecision(){
        Foreground.apply(root,true,true,tracked,true);
        Foreground.apply(root,false,true,null,true);
        idle(400);assertEquals(0xffffffff,title.getCurrentTextColor());
        Foreground.apply(root,false,true,null,false);
        Foreground.apply(root,true,true,tracked,true);
        Foreground.apply(root,false,true,null,true);
        Foreground.apply(root,true,true,tracked,true);
        idle(400);assertEquals(0xff000000,title.getCurrentTextColor());
        for(int i=0;i<10;i++){Foreground.apply(root,i%2==0?false:true,true,tracked,true);}
        idle(400);
        // Ten rapid flips: one final state, no orphaned transition, no grey.
        assertFalse(InkAnimator.running(title));
        assertEquals(0xff000000,title.getCurrentTextColor());
    }
    @Test public void nativeRebindDuringTransitionWins(){
        Foreground.apply(root,true,true,tracked,true);
        // SystemUI binds a new theme colour: redirected to its mirror, the transition is dropped.
        Integer redirected=Foreground.interceptColor(title,0xffeeeeee);
        assertEquals(Integer.valueOf(0xff111111),redirected);
        title.setTextColor(redirected);
        idle(400);
        assertEquals(0xff111111,title.getCurrentTextColor());
        assertFalse(InkAnimator.running(title));
    }
    @Test public void immediateWhenNotAnimated(){
        Foreground.apply(root,true,true,tracked,false);
        assertFalse(InkAnimator.running(title));
        assertEquals(0xff000000,title.getCurrentTextColor());
    }
    @Test public void chevronTransitionsAndRestoresItsFilters(){
        ImageView icon=new ImageView(activity);
        int id=icon.getResources().getIdentifier("expand_button_icon","id","android");icon.setId(id);
        ColorDrawable d=new ColorDrawable(0xffffffff);ColorFilter own=new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN);
        d.setColorFilter(own);icon.setImageDrawable(d);icon.setImageAlpha(190);root.addView(icon);
        Foreground.apply(icon,true,true,tracked,true);
        assertTrue(InkAnimator.running(icon));
        idle(400);
        assertEquals(0xff000000,icon.getImageTintList().getDefaultColor());
        assertNull(Foreground.drift(tracked));
        Foreground.apply(icon,false,true,null,true);
        idle(400);
        assertNull(icon.getImageTintList());assertSame(own,icon.getDrawable().getColorFilter());assertEquals(190,icon.getImageAlpha());
    }
    @Test public void restoreOverriddenBySystemUiKeepsItsColourAndGivesBackTheShadow(){
        title.setShadowLayer(4f,0f,1f,0x80000000);
        Foreground.apply(root,true,true,tracked,false);
        assertEquals(0f,title.getShadowRadius(),0f);
        Foreground.apply(root,false,true,null,true);
        // SystemUI rebinds mid-restore: its colour stands, the shadow is given back.
        title.setTextColor(0xff3d7bff);
        idle(400);
        assertEquals(0xff3d7bff,title.getCurrentTextColor());
        assertEquals(4f,title.getShadowRadius(),0f);
        // A later swap starts from SystemUI's colour, never from the stale saved list.
        Foreground.apply(root,true,true,tracked,false);
        assertEquals(0xff3d7bff,title.getCurrentTextColor());
    }
    @Test public void chevronRestoreOverriddenBySystemUiLeavesNoStaleFilter(){
        ImageView icon=chevron();ColorFilter own=icon.getDrawable().getColorFilter();
        Foreground.apply(icon,true,true,tracked,false);
        Foreground.apply(icon,false,true,null,true);
        icon.setImageTintList(ColorStateList.valueOf(0xff3d7bff));
        idle(400);
        assertNull("no module filter left over the new tint",icon.getColorFilter());
        assertSame(own,icon.getDrawable().getColorFilter());
        assertEquals(0xff3d7bff,icon.getImageTintList().getDefaultColor());
    }
    @Test public void chevronSwappedAgainWhileRestoringEndsSwappedThenRestoresExactly(){
        ImageView icon=chevron();ColorFilter own=icon.getDrawable().getColorFilter();
        Foreground.apply(icon,true,true,tracked,false);
        Foreground.apply(icon,false,true,null,true);
        Foreground.apply(icon,true,true,tracked,true);
        idle(400);
        assertEquals(0xff000000,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null,false);
        assertNull(icon.getImageTintList());assertNull(icon.getColorFilter());assertSame(own,icon.getDrawable().getColorFilter());
    }
    private ImageView chevron(){
        ImageView icon=new ImageView(activity);
        icon.setId(icon.getResources().getIdentifier("expand_button_icon","id","android"));
        ColorDrawable d=new ColorDrawable(0xffffffff);d.setColorFilter(new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN));
        icon.setImageDrawable(d);root.addView(icon);return icon;
    }
}

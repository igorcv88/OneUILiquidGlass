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
        // The setter keeps the displayed colour while changing this transition's target.
        Integer redirected=Foreground.interceptColor(title,0xffeeeeee);
        assertEquals(Integer.valueOf(title.getCurrentTextColor()),redirected);
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
    @Test public void sharedClockKeepsTextChevronAndBothGlyphRoutesInPhase() {
        ImageView expand = chevron();
        ImageView icon = new ImageView(activity);
        icon.setId(android.R.id.icon);
        ColorStateList original = ColorStateList.valueOf(Color.WHITE);
        icon.setImageDrawable(new ColorDrawable(Color.WHITE));
        icon.setImageTintList(original);
        root.addView(icon);
        ImageView managed = new ImageView(activity);
        root.addView(managed);
        NotificationIconTreatment.Lease lease = new NotificationIconTreatment.Lease(
                managed, new ColorDrawable(Color.WHITE), Color.WHITE);

        InkAnimator.Timeline down = new InkAnimator.Timeline();
        Foreground.apply(root, true, true, tracked, true, down);
        lease.tone(Color.BLACK, down, false);
        down.sealed = true;
        idle(70);
        int intermediate = title.getCurrentTextColor();
        assertNotEquals(Color.WHITE, intermediate);
        assertNotEquals(Color.BLACK, intermediate);
        assertEquals(intermediate, expand.getImageTintList().getDefaultColor());
        assertEquals(Integer.valueOf(intermediate), Foreground.filterColor(icon.getDrawable().getColorFilter()));
        assertEquals(intermediate, lease.shownInk);

        InkAnimator.Timeline up = new InkAnimator.Timeline();
        Foreground.apply(root, false, true, tracked, true, up);
        lease.tone(Color.WHITE, up, false);
        up.sealed = true;
        assertEquals(intermediate, title.getCurrentTextColor());
        assertEquals(Integer.valueOf(intermediate), Foreground.filterColor(icon.getDrawable().getColorFilter()));
        assertEquals(intermediate, lease.shownInk);
        idle(60);
        assertEquals(title.getCurrentTextColor(), expand.getImageTintList().getDefaultColor());
        assertEquals(title.getCurrentTextColor(), lease.shownInk);
        idle(250);
        assertEquals(Color.WHITE, title.getCurrentTextColor());
        assertEquals(Color.WHITE, lease.shownInk);
        assertSame(original, icon.getImageTintList());
        assertFalse(InkAnimator.running(icon));
        assertFalse(InkAnimator.running(expand));
        lease.restore();
    }

    @Test public void aReinflatedTitleJoinsTheInFlightCardDeadline() {
        InkAnimator.Timeline transition = new InkAnimator.Timeline();
        Foreground.apply(root, true, true, tracked, true, transition);
        transition.sealed = true;
        idle(65);
        TextView rebound = new TextView(activity);
        rebound.setTextColor(Color.WHITE);
        root.addView(rebound);
        Foreground.apply(root, true, true, tracked, true, transition);
        idle(32);
        assertEquals(title.getCurrentTextColor(), rebound.getCurrentTextColor());
        idle(250);
        assertEquals(Color.BLACK, rebound.getCurrentTextColor());
        assertFalse(InkAnimator.running(rebound));
    }

    private ImageView chevron(){
        ImageView icon=new ImageView(activity);
        icon.setId(icon.getResources().getIdentifier("expand_button_icon","id","android"));
        ColorDrawable d=new ColorDrawable(0xffffffff);d.setColorFilter(new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN));
        icon.setImageDrawable(d);root.addView(icon);return icon;
    }
}

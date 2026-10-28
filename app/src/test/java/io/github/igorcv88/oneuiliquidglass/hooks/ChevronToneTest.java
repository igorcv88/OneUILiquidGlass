package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ChevronToneTest {
    ImageView icon;List<android.view.View> tracked;
    @Before public void setup(){icon=new ImageView(RuntimeEnvironment.getApplication());
        int id=icon.getResources().getIdentifier("expand_button_icon","id","android");assertTrue(id!=0);
        icon.setId(id);icon.setImageDrawable(new ColorDrawable(0xffffffff));tracked=new ArrayList<>();}
    @After public void restore(){Foreground.apply(icon,false,true,null);}
    @Test public void alternateIdMustBeTheFrameworkExpandControlsActualIcon() throws Exception {
        Class<?> type=Class.forName("com.android.internal.widget.NotificationExpandButton");
        android.view.ViewGroup parent=(android.view.ViewGroup)type.getConstructor(android.content.Context.class,android.util.AttributeSet.class)
                .newInstance(RuntimeEnvironment.getApplication(),null);
        java.lang.reflect.Field owned=type.getDeclaredField("mIconView");owned.setAccessible(true);
        icon.setId(android.view.View.generateViewId());parent.addView(icon);owned.set(parent,icon);
        Foreground.apply(icon,true,true,tracked);assertEquals(1,tracked.size());
        Foreground.apply(icon,false,true,null);tracked.clear();
        ImageView unrelated=new ImageView(RuntimeEnvironment.getApplication());unrelated.setImageDrawable(new ColorDrawable(0xffffffff));parent.addView(unrelated);
        Foreground.apply(unrelated,true,true,tracked);assertTrue(tracked.isEmpty());assertNull(unrelated.getImageTintList());
    }
    @Test public void unknownEffectiveFilterUsesNativeFallbackInsteadOfGuessingFromTint(){
        ColorStateList tint=ColorStateList.valueOf(0xffffffff);icon.setImageTintList(tint);
        ColorFilter unknown=new ColorMatrixColorFilter(new ColorMatrix());icon.setColorFilter(unknown);
        Foreground.apply(icon,true,true,tracked);
        assertTrue(tracked.isEmpty());assertSame(tint,icon.getImageTintList());assertSame(unknown,icon.getColorFilter());
    }
    @Test public void explicitFilterWinsOverConflictingTintAndRestoresBoth(){
        ColorStateList tint=ColorStateList.valueOf(0xff111111);icon.setImageTintList(tint);
        ColorFilter filter=new PorterDuffColorFilter(0xffeeeeee,PorterDuff.Mode.SRC_IN);
        icon.setColorFilter(filter);icon.setImageAlpha(173);
        Foreground.apply(icon,true,true,tracked);
        assertEquals(0xff111111,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null);
        assertSame(tint,icon.getImageTintList());assertSame(filter,icon.getColorFilter());assertEquals(173,icon.getImageAlpha());
    }
    @Test public void replacingDrawableRestoresTheDetachedOriginal(){
        ColorDrawable original=(ColorDrawable)icon.getDrawable();
        ColorFilter filter=new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN);original.setColorFilter(filter);
        Foreground.apply(icon,true,true,tracked);
        ColorDrawable replacement=new ColorDrawable(0xffffffff);
        Foreground.beforeChevronDrawable(icon,replacement);assertSame(filter,original.getColorFilter());
        icon.setImageDrawable(replacement);Foreground.apply(icon,true,true,tracked);
        Foreground.apply(icon,false,true,null);assertNull(icon.getDrawable().getColorFilter());
    }
    @Test public void drawableFilterWithoutTintListIsRecolouredAndRestored(){
        ColorFilter original=new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN);
        icon.getDrawable().setColorFilter(original);icon.setImageAlpha(201);
        Foreground.apply(icon,true,true,tracked);
        assertEquals(1,tracked.size());assertEquals(0xff000000,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null);
        assertNull(icon.getImageTintList());assertSame(original,icon.getDrawable().getColorFilter());assertEquals(201,icon.getImageAlpha());
    }
    @Test public void tintStateListSurvivesNativeRebindAndRestore(){
        ColorStateList original=ColorStateList.valueOf(0xffeeeeee);icon.setImageTintList(original);
        Foreground.apply(icon,true,true,tracked);
        ColorStateList rebound=ColorStateList.valueOf(0xffdddddd);icon.setImageTintList(rebound);
        Foreground.apply(icon,true,true,tracked);assertEquals(0xff222222,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null);assertSame(rebound,icon.getImageTintList());
    }
    @Test public void replacementDrawableKeepsItsOwnOriginalFilter(){
        Foreground.apply(icon,true,true,tracked);
        ColorDrawable replacement=new ColorDrawable(0xffffffff);
        ColorFilter nativeFilter=new PorterDuffColorFilter(0xffeeeeee,PorterDuff.Mode.SRC_IN);
        replacement.setColorFilter(nativeFilter);
        Foreground.beforeChevronDrawable(icon,replacement);icon.setImageDrawable(replacement);
        Foreground.apply(icon,true,true,tracked);Foreground.apply(icon,false,true,null);
        assertSame(nativeFilter,icon.getDrawable().getColorFilter());
    }
    @Test public void arbitraryAppImageIsNotRecoloured(){
        icon.setId(android.view.View.NO_ID);Foreground.apply(icon,true,true,tracked);
        assertTrue(tracked.isEmpty());assertNull(icon.getImageTintList());assertNull(icon.getDrawable().getColorFilter());
    }
}
